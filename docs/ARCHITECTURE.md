# Architecture

## Phase 9C scope

Phase 9A added worker telemetry and scheduler-oriented execution history on
top of the existing distributed job pipeline. Phase 9B begins using those
inputs for conservative placement while preserving the same pull-based worker
protocol and PostgreSQL row-locking queue. It deliberately separates:

- **Capability** — whether a worker can execute a workload.
- **Capacity** — how busy or resource-constrained the worker is right now.
- **Performance history** — how previous attempts performed for similar
  workloads.

Phase 9B uses capability, fresh capacity, memory/CPU telemetry when available,
and bounded recent execution history. It does not implement predictive
placement, autoscaling, GPU scheduling, queue priority redesign, or RabbitMQ
dispatch.

Phase 9C observes this unchanged scheduler. Every successful claim persists a
compact `scheduling_decisions` row in the assignment transaction with the
policy, actual scalar score, controlled reason codes, telemetry/fallback and
starvation flags, attempt, and bounded capacity snapshot. Empty polls are only
debug events. Read-only observability queries never use pessimistic locks.

Execution aggregates remain per attempt and use Phase 9A definitions:
`queueWaitMs = assignedAt - queuedAt`, `executionMs = finishedAt - startedAt`,
and `totalLatencyMs = finishedAt - queuedAt`. APIs accept only `1h`, `24h`,
`7d`, or `30d` windows and always scope rows through the authenticated workspace.
Decision cleanup runs once daily and retains 30 days by default; execution
metrics are not deleted by Phase 9C.

## High-level architecture

```
Angular Login + Dashboard
        |
        v
Spring Boot Control Plane + Session Auth
        |
   +----+----+--------+
   |         |        |
Postgres   RabbitMQ  MinIO / S3-compatible storage
   ^
   |
Workers ---- presigned PUT/GET mediated by API
       /              \
Local Laptop       Cloud Worker
```

- **Angular Login + Dashboard** — the browser-facing UI. It never talks to
  the backend directly; it goes through Nginx.
- **Spring Boot Control Plane** — a single deployable modular monolith. It
  owns authentication, workspace context, the database schema, and is the only
  thing that talks to Postgres and RabbitMQ.
- **Postgres** — system of record for the control plane, media asset metadata,
  and the durable job queue.
- **RabbitMQ** — available infrastructure reserved for a later event-driven
  dispatch optimization. The platform intentionally continues to use PostgreSQL row locking
  because the database must remain the source of truth for job state anyway.
- **MinIO / S3-compatible storage** — private object storage for imported
  media binaries. The local stack uses MinIO; the storage abstraction can point
  at S3/R2-compatible storage later.
- **Workers** — interchangeable compute resources (a laptop, a cloud VM,
  anything that can run the worker process). They register, heartbeat, poll
  for jobs, execute `SYSTEM_TEST` and `IMPORT_MEDIA`, optionally execute
  `INSPECT_MEDIA` when FFprobe is available, FFmpeg derivatives when FFmpeg is
  available, deterministic highlight analysis, and media transcription when a
  configured local transcription provider is available.

## Request flow

```
Browser
   |
   v
Nginx
   |
    +---- /api/* ----> Spring Boot (api:8080, session + CSRF)
   |
   +---- /* --------> Angular  (web:80)
```

The browser only ever knows about the Nginx host/port. It never sees the
`api` or `web` container hostnames — those exist purely on the internal
Docker network. This is what lets the backend, frontend, and edge proxy be
deployed, scaled, or replaced independently later without changing anything
the browser does.

## Authentication and workspace context

Authentication uses Spring Security with email/password login, BCrypt password
hashes, and server-side HTTP sessions. The browser stores only cookies; it
does not store credentials, password hashes, or bearer tokens in localStorage.

Spring Security keeps CSRF enabled. The backend publishes an `XSRF-TOKEN`
cookie for the Angular SPA, and Angular sends the matching `X-XSRF-TOKEN`
header on protected mutating requests. The session cookie is `HttpOnly`;
production marks it `Secure`.

The backend derives the current user from the session principal. Workspace
access is then checked against `workspace_memberships`; client-supplied
workspace IDs are never trusted without validating membership. Phase 2 chooses
the first membership as the current workspace, leaving explicit workspace
switching for a later phase.

Worker agents use a separate machine-token path under `/api/worker-agent/**`.
Those endpoints do not use browser sessions or CSRF cookies, and CSRF remains
enabled for browser APIs. Worker credentials belong to one workspace, are
stored as BCrypt hashes, and authorize only worker registration/heartbeat.
Human APIs such as `GET /api/workers` remain session-protected and scoped to
the user's current workspace membership.

Human job APIs (`/api/jobs`) and asset APIs (`/api/assets`) use the same
session and CSRF path. The workspace for create/list/detail/cancel/import is
derived from the authenticated membership; clients cannot choose an arbitrary
`workspaceId`.

Worker job APIs (`/api/worker-agent/jobs/**`), worker import APIs
(`/api/worker-agent/assets/imports/**`), and worker inspection APIs
(`/api/worker-agent/assets/inspections/**`) use `WorkerToken` machine
authentication. The credential's workspace is authoritative, the worker must
already be registered and currently online, and a worker may only update jobs
assigned to itself.

## Modular monolith

The backend (`apps/api-spring`) is a single Spring Boot application,
deliberately not split into microservices yet. It is organized into
top-level packages that map to future bounded contexts:

```
com.fdmultimedia.api
├── auth          — session authentication, bootstrap, and auth DTOs
├── users         — user accounts and email normalization
├── workspaces    — workspaces / tenants and membership authorization
├── accounts      — connected external (social) accounts
├── robots        — logical content-automation entities
├── assets        — media assets
├── jobs          — distributed processing jobs
├── workers       — worker registration and management
├── publishing    — publishing to external platforms
├── analytics     — analytics and reporting
├── revenue       — revenue tracking and attribution
└── shared        — cross-cutting concerns (web, config, health)
```

Packages outside `auth`, `users`, `workspaces`, `jobs`, `workers`, and
`shared` are still placeholders today. The intent is that as each capability
is built, its code lands in the matching package with a clear boundary.

## Job lifecycle and worker protocol

Jobs are persisted in PostgreSQL with JSONB `payload` and `result` fields.
`SYSTEM_TEST` accepts a bounded message and duration. `IMPORT_MEDIA`,
`INSPECT_MEDIA`, `ANALYZE_HIGHLIGHTS`, and `TRANSCRIBE_MEDIA` accept only
asset/transcript provider references;
source URL, storage state, inspection metadata, and highlight candidates live
in their own domain tables. No job type executes shell commands or arbitrary
code.

Allowed state transitions:

- `QUEUED -> ASSIGNED` when one online worker claims the job.
- `ASSIGNED -> RUNNING` when that worker acknowledges start.
- `RUNNING -> SUCCEEDED` when that worker reports a result.
- `ASSIGNED|RUNNING -> QUEUED` when a retryable failure or expired lease still
  has attempts remaining.
- `ASSIGNED|RUNNING -> FAILED` when attempts are exhausted.
- `QUEUED|ASSIGNED|RUNNING -> CANCELLED` when a human cancels an active job.

`SUCCEEDED`, `FAILED`, and `CANCELLED` are terminal and never transition back
to active states.

Workers poll `POST /api/worker-agent/jobs/claim`. The claim transaction first
recovers expired leases for that workspace, then selects the oldest queued job
with `FOR UPDATE SKIP LOCKED` and assigns it to the worker. That prevents two
workers from claiming the same queued row concurrently without introducing a
separate broker-level dispatch protocol.

Capability eligibility is isolated behind `WorkerEligibilityService`. It
normalizes the worker-reported supported job types and highlight analyzers,
preserves legacy defaults (`SYSTEM_TEST` and `DETERMINISTIC_V1`), and feeds
the locked queue lookup.

Scheduling policy `TELEMETRY_AWARE_V1` is implemented in
`WorkerSchedulingService`. A worker claim transaction recovers expired leases,
locks a bounded FIFO window of compatible queued jobs with
`FOR UPDATE SKIP LOCKED`, then chooses one locked candidate for the polling
worker. This keeps the database concurrency guarantee intact; the server never
does an unsafe load-all-workers / pick-one / update-later assignment.

The scheduler uses:

- explicit worker capacity (`maxActiveJobs`, default `1`)
- fresh `activeJobs` telemetry as a hard capacity gate
- memory pressure, with only dangerous low-memory states treated as a hard
  rejection for non-starved heavier jobs
- CPU load as a soft signal only when available
- recent successful `executionMs` history after a minimum sample count
- starvation protection so old queued jobs eventually bypass soft preferences

Missing or stale telemetry degrades to FIFO rather than bricking a worker.
Failure-rate scoring is intentionally deferred until the failure taxonomy is
stable enough to avoid misleading scores.

Claims set `lease_expires_at` and increment `attempt_count`. Starting and
renewing a job refreshes the lease. The worker renews leases while long-running
imports are active, so legitimate downloads do not look abandoned. If a laptop
disappears after claim/start and stops renewing, the next claim for that
workspace lazily recovers expired active jobs: retryable jobs return to
`QUEUED`, while jobs that exhausted `max_attempts` become `FAILED`. This is
deliberately simple and avoids a distributed scheduler in Phase 9A.

## Worker telemetry, scheduling, and execution metrics

Worker registration stores static metadata:

- machine identifier
- worker name
- operating system and architecture
- CPU model and logical cores
- total memory
- optional GPU model/memory
- agent version
- maximum active jobs

Heartbeat stores dynamic operational telemetry when the current agent can
collect it cheaply:

- system CPU load (`0..1`) when the JVM/OS exposes it
- process CPU load (`0..1`) when available
- available system memory
- JVM heap used and max
- active job count
- current supported job types and highlight analyzers
- `lastTelemetryAt`
- current maximum active jobs when reported by the agent

Telemetry fields are optional and sanitized. Invalid CPU loads, negative
memory, impossible available-memory values, or pathological active-job counts
are nulled rather than making an otherwise valid heartbeat fail. Heartbeat
requests without telemetry remain valid for rolling upgrades. Telemetry is
fresh for `app.scheduling.telemetry-freshness-window`; stale measurements are
still stored for debugging but are not returned as current values in the
Compute API.

Telemetry is not security-authoritative. Worker machine authentication remains
the security boundary; a worker can report inaccurate load, so telemetry is
used only as future scheduling input.

The control plane records lightweight `JobExecutionMetric` rows when an attempt
succeeds, fails, or is recovered after lease expiry. The definitions are:

- `queueWaitMs = assignedAt - queuedAt`
- `executionMs = finishedAt - startedAt` when the worker acknowledged start
- `totalLatencyMs = finishedAt - queuedAt`

Metrics are per attempt. When a lease expires on worker A and the job later
succeeds on worker B, the expired attempt is attributed to worker A and the
successful attempt is attributed to worker B. Retry/requeue transitions capture
a snapshot before resetting job timestamps, so history is not accidentally
rewritten by recovery. Workload hints are nullable and only use data the
platform already has: media size/duration/dimensions, requested clip duration,
or provider/model/analyzer identifiers. The scheduler only uses `executionMs`
from successful attempts for Phase 9B performance history; queue wait is not
used as a worker-performance signal. The platform still does not store every
heartbeat forever.

## MediaAsset lifecycle and object storage

`MediaAsset` rows belong to a workspace and record the original direct URL,
status, created user, linked import and inspection jobs, storage bucket/key,
checksum, size, content type, and basic metadata.

Allowed asset transitions:

- `PENDING -> IMPORTING` when an assigned worker requests import authorization.
- `IMPORTING -> READY` when that worker completes the assigned import job.
- `IMPORTING -> PENDING` after a retryable worker failure while attempts remain.
- `PENDING|IMPORTING -> FAILED` for terminal validation failures or exhausted
  retries.

`READY` is terminal for import in Phase 6A and means a private original object exists in
object storage. Server-generated storage keys use
`workspaces/{workspaceId}/assets/{assetId}/original`; user filenames are stored
only as metadata and never influence object paths.

Inspection status is tracked separately from import status:

- `NOT_REQUESTED` for older assets or assets not yet ready for inspection.
- `PENDING` when the API creates an `INSPECT_MEDIA` job after import success.
- `INSPECTING` when an assigned FFprobe-capable worker requests authorization.
- `INSPECTED` when FFprobe metadata is persisted successfully.
- `FAILED` when inspection cannot complete after terminal validation failure or
  exhausted retries.

Inspection failure does not undo `READY`; the stored original remains usable,
and the failure is recorded in `inspection_error_code` /
`inspection_error_message`.

The API owns permanent object-storage credentials. Workers request a short-lived
presigned PUT URL, upload the downloaded file directly, and report the bucket
and key they were authorized to use. The server verifies worker authentication,
job ownership, workspace, asset/job matching, storage bucket/key, checksum
shape, and legal state before marking the asset `READY` and the job
`SUCCEEDED`. Browser users request short-lived presigned GET URLs for READY
assets; MinIO buckets remain private and signed URLs are not stored.

`IMPORT_MEDIA` retry behavior is intentionally bounded. Retryable failures
such as temporary source or storage failures requeue the job until
`maxAttempts`; terminal failures such as SSRF blocks, invalid schemes,
non-media content, and oversized files fail the asset and job immediately.

## URL and download security

The API validates direct import URLs before asset creation and the worker
revalidates before every download and redirect. Only `http` and `https` are
allowed. Embedded credentials, localhost, loopback, private IPv4 ranges,
private/link-local IPv6 ranges, multicast/reserved addresses, and cloud
metadata endpoints are rejected. The worker follows only a small bounded number
of redirects and revalidates each target to avoid blindly trusting the initial
host.

Downloads use connection/read timeouts and stream to a temporary file while
enforcing `MEDIA_MAX_DOWNLOAD_SIZE_BYTES`, even when `Content-Length` is
missing or wrong. Temporary files are deleted in success and failure paths.
The worker rejects clearly non-media response types such as HTML, JSON, XML,
and text. Phase 5 records size, SHA-256, content type, safe original filename,
and container-like information inferred from content type.

Phase 6A adds FFprobe strictly for read-only inspection of the stored original.
The worker first verifies FFprobe availability with `ffprobe -version`; only
then does it advertise the `INSPECT_MEDIA` capability during job claim. Workers
without FFprobe continue to import media and run system tests but cannot claim
inspection jobs. The inspector invokes FFprobe through `ProcessBuilder` with a
fixed argument list and no shell:

```text
ffprobe -v error -print_format json -show_format -show_streams <file>
```

The parsed metadata includes duration, width, height, video codec, audio codec,
container format, frame rate, bitrate, and `hasVideo`/`hasAudio`. Primary video
selection ignores attached-picture streams so album art is not mistaken for a
video track. Unsupported or invalid FFprobe output is recorded as a controlled
inspection failure without exposing stack traces or credentials.

Phase 6B adds `CREATE_CLIP` as the first controlled FFmpeg derivative. The
server creates an output `MediaAsset` before queuing the job, stores
`parent_asset_id` and `derivation_type=CLIP`, and keeps the original asset
immutable. The job payload contains only `sourceAssetId`, `outputAssetId`,
`startMs`, and `durationMs`; raw FFmpeg options are never accepted from users
or workers.

Clip assets move `PENDING -> PROCESSING -> READY`, or `PROCESSING -> PENDING`
for retryable failures, or `PENDING|PROCESSING -> FAILED` for terminal failure
or exhausted attempts. Once a derived clip becomes READY, the API reuses the
existing inspection chain and creates an `INSPECT_MEDIA` job for that output.

Workers advertise `CREATE_CLIP` only when `ffmpeg -version` succeeds. The
worker obtains a presigned GET for the source and a presigned PUT for the
server-derived output key, downloads with bounded streaming, invokes FFmpeg
through `ProcessBuilder` with fixed arguments and no shell, uploads a new MP4,
and reports checksum/size. The first profile is H.264 video, AAC audio, MP4
container, and `-ss` after `-i` to favor more accurate timing. The requested
interval is `[startMs, startMs + durationMs)` with normal codec/container
tolerance after re-encoding. FFmpeg output capture is bounded but drained, temp
paths are redacted from worker error messages, and temp source/output files are
deleted on success and failure paths.

Phase 6C adds `CREATE_SOCIAL_VERTICAL` as a second controlled FFmpeg derivative
using the same job, lease, retry, presigned storage, checksum, and automatic
inspection flow. The API creates a new output asset with
`derivation_type=SOCIAL_VERTICAL` and `parent_asset_id` set to the selected
source. The source can be an original or another derivative; lineage always
uses the immediate selected parent rather than jumping to the root original.

The preset accepts no user dimensions, crop points, filter expressions, or raw
FFmpeg arguments. The worker invokes FFmpeg without a shell and uses this fixed
video filter:

```text
scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920
```

That scales the source until the 1080x1920 canvas is covered while preserving
aspect ratio, then center-crops any excess. It intentionally does not
letterbox, stretch, track subjects, or use AI reframing. Output is MP4/H.264,
with AAC audio when the source has audio; video-only input remains valid and
audio-only input is rejected by the API because the preset requires video.
The transformation changes geometry only and preserves source duration within
normal encoding/container tolerance. Standard FFmpeg autorotation behavior is
relied on for common phone-video rotation metadata; a larger orientation
subsystem is deferred until there is a real need.

## Highlight analysis and candidates

Phase 7A introduces `HighlightAnalysis` and `HighlightCandidate` as persisted
domain concepts. An analysis belongs to one workspace and one inspected video
asset, owns exactly one `ANALYZE_HIGHLIGHTS` job, and moves through:

- `PENDING` when the browser requests analysis.
- `RUNNING` when an assigned worker requests authorization.
- `SUCCEEDED` when validated candidates are persisted atomically.
- `FAILED` when terminal failure or exhausted retries occur.

Candidates belong to an analysis and asset. They store `startMs`, `endMs`,
`score`, `reason`, deterministic `rank`, and `createdAt`. A candidate is a
recommendation only; it does not create or store media. When a user chooses a
candidate, `POST /api/highlight-candidates/{id}/create-clip` converts
`startMs` and `endMs - startMs` into the existing `CREATE_CLIP` service path.

The Phase 7A analyzer abstraction is intentionally small:
`HighlightAnalyzer.analyze(input) -> HighlightAnalysisResult`. The Java worker
ships `DeterministicHighlightAnalyzer`, identified as `DETERMINISTIC_V1`,
which proposes up to three intervals around fixed timeline percentages using
only authorized asset duration metadata. It does not download media, call AI
providers, transcribe audio, run vision models, or construct FFmpeg arguments.

The backend treats worker/analyzer output as untrusted because later phases may
replace the deterministic analyzer with an AI/provider implementation. It
validates candidate count, interval bounds, duration limits, score range,
reason length, workspace/job/asset ownership, legal job state, and stale worker
ownership before replacing candidate rows and completing the job. Ranking is
server-side: score descending with deterministic start/end/reason tie-breaks.
Expired analysis leases are reconciled with the analysis domain state in the
same lazy recovery path as imports, inspections, clips, and vertical presets.

## Media transcripts

Phase 7B1 introduces a reusable transcript domain:

- `MediaTranscript` belongs to one workspace and asset, owns one
  `TRANSCRIBE_MEDIA` job, and stores provider/model, status, detected language,
  timing, and safe error information.
- `TranscriptSegment` stores ordered timestamped text with optional confidence.

Transcript lifecycle is `PENDING -> RUNNING -> SUCCEEDED` or `FAILED`.
Retryable failures return the same transcript to `PENDING`; terminal failure
marks that transcript `FAILED`. A retry never creates Transcript2/Transcript3
for the same job. Active duplicate requests for the same asset/provider/model
reuse the existing active transcript instead of creating duplicate expensive
work.

The server validates that the source asset is READY, INSPECTED, has audio, and
has known positive duration. Worker completion is untrusted: the backend
validates segment count, timestamp order, bounded overlap, `endMs <= duration`
with a small tolerance, non-empty bounded text, total transcript text size, and
confidence range before replacing segment rows and completing the job in one
transaction. Stale workers are rejected through the existing row-locked job
ownership checks.

The first worker provider is a local Whisper-compatible CLI behind
`TranscriptionProvider`. The Java worker is used because it already owns the
hardened job loop, lease renewal, FFmpeg process handling, and presigned media
download code. Python is not required by the server protocol and can be added
later as another worker implementation. The worker supports the Python
`whisper` CLI contract (`WHISPER_CLI`) and the native `whisper.cpp`
`whisper-cli` JSON contract (`WHISPER_CPP`). The worker advertises
`TRANSCRIBE_MEDIA` only when FFmpeg is available and the configured provider is
available within a bounded timeout; the whisper.cpp adapter also requires the
configured model file to exist. Startup never downloads models automatically.

## Semantic highlight analysis

Phase 7B2 extends the existing `ANALYZE_HIGHLIGHTS` job rather than creating a
new queue. The default analyzer remains `DETERMINISTIC_V1` for Phase 7A
compatibility. A client can request `TRANSCRIPT_SEMANTIC_V1`, which requires a
READY + INSPECTED video asset, known duration, audio, and a `SUCCEEDED`
transcript with persisted segments.

Workers now advertise supported highlight analyzers separately from job types.
The claim query filters `ANALYZE_HIGHLIGHTS` by the analyzer stored in job
payload, so a worker that only supports `DETERMINISTIC_V1` cannot claim a
semantic job. Legacy workers default to deterministic support only.

Semantic analysis uses a provider boundary in the worker. The first provider is
a local Ollama HTTP runtime configured with `SEMANTIC_HIGHLIGHT_RUNTIME=OLLAMA`,
`SEMANTIC_HIGHLIGHT_ENDPOINT`, and `SEMANTIC_HIGHLIGHT_MODEL`. The provider
receives transcript-derived windows and returns structured candidate references.
It never receives media binaries, presigned URLs, storage keys, worker tokens,
object-storage credentials, or arbitrary commands. The worker does not execute
a shell for semantic analysis.

The backend treats semantic output as untrusted. It verifies the worker owns the
job, the transcript belongs to the same asset/workspace, candidate windows are
inside the asset duration, and semantic candidates overlap transcript segments.
Near-boundary suggestions can be snapped to transcript boundaries within a
small tolerance; ungrounded suggestions are rejected. Candidate ranking remains
server-side and candidate-to-clip still uses the existing `CREATE_CLIP`
pipeline only after explicit user choice.

## An important architectural rule: Robots are not workers

A **Robot** is a logical content-automation entity — think of it as "a
personality/pipeline that posts to a specific channel" or "an automated
workflow the user configured." A **Worker** is a physical or virtual compute
resource (a laptop, a cloud VM) that executes jobs.

**A Robot MUST NOT be coupled 1:1 to a physical worker.** Workers are
interchangeable, disposable compute — any worker with the right
capabilities should be able to pick up any job for any robot. A robot's
identity, configuration, and history must never depend on which specific
machine happened to run its jobs. This separation is what allows workers to
be added, removed, or replaced (a laptop goes offline, a cloud instance is
scaled up) without affecting the robots whose jobs they process.

Phase 4 lets workers execute generic platform jobs, but still does not tie
robots to workers. `SYSTEM_TEST` is a controlled pipeline proof, not media
execution or robot automation.
