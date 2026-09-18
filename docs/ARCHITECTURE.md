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

## Phase 10A scope

Phase 10A adds the secure domain model and distributed publishing pipeline
foundation for eventually publishing generated media to Instagram/TikTok —
deliberately **not** real social posting yet. It introduces `SocialAccount`,
`Publication`/`PublishingAttempt`, and a new `PUBLISH_MEDIA` Job type that
flows through the existing Job/worker/scheduling infrastructure unchanged,
proven end-to-end by a deterministic, explicitly non-real `TEST` provider. See
[Social accounts and publishing (Phase 10A)](#social-accounts-and-publishing-phase-10a)
below for the full design. Out of scope for this phase: real Instagram/TikTok
APIs, browser automation, unofficial platform APIs, scheduled/recurring
posting, AI-generated captions, and multi-platform fan-out.

## Phase 10B scope

Phase 10B connects the very first real provider — Instagram — to the Phase
10A foundation, using only Meta's official "Instagram API with Instagram
Login" and Content Publishing API (video/Reels). TEST keeps working
unchanged. See
[Instagram publishing (Phase 10B)](#instagram-publishing-phase-10b) below for
the full design: OAuth start/callback with a hashed, single-use, expiring
state; AES-256-GCM credential encryption with no plaintext fallback; a
credential trust boundary that never hands the Worker a token (all Graph API
calls stay backend-side; the Worker only drives a bounded poll loop);
provider-aware Worker capability gating enforced as a hard gate in the Job
claim SQL; unguessable, publication-scoped, self-expiring public media
delivery tokens that never make the MinIO bucket public; and
container-id-based reconciliation to avoid duplicate real posts on retry.
Out of scope for this phase: TikTok, YouTube, Facebook publishing, Stories,
carousels, image posts, engagement automation, scheduled/recurring
publishing, and AI-generated captions/hashtags.

## Phase 11A scope

Phase 11A turns the separate technical actions built in Phases 7–10B (clip,
social vertical, highlight candidate, publish) into one coherent product
workflow: a workspace-scoped `ContentDraft` that represents content being
prepared for publishing. It is deliberately not a Job, a Publication, a
MediaAsset, or a Robot. See
[Content drafts (Phase 11A)](#content-drafts-phase-11a) below for the full
design: the two creation paths (an already-eligible asset, or a highlight
candidate that starts a clip derivation immediately); durable,
row-lock-guarded workflow reconciliation that advances the draft only when
the derivative it is waiting on reaches a terminal state, so repeated polling
or a restart never queues a duplicate `CREATE_CLIP`/`CREATE_SOCIAL_VERTICAL`
Job; the caption-snapshot rule that keeps a `Publication` immutable once
created even as the draft's own caption keeps being edited; and how a failed
Publication reverts the draft to READY instead of destroying it. Out of scope
for this phase: autonomous Robots, automatic/scheduled publishing, a generic
workflow engine, TikTok, and AI-generated captions.

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
  available, deterministic highlight analysis, media transcription when a
  configured local transcription provider is available, and `PUBLISH_MEDIA`
  through the deterministic, non-real `TEST` publishing provider. A Worker
  also drives real Instagram publishing when explicitly opted in via
  `WORKER_INSTAGRAM_PUBLISHING_ENABLED` — it never holds an Instagram
  credential either way; see Phase 10B below.

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
├── accounts      — connected external (social) accounts, credentials, OAuth state
├── robots        — logical content-automation entities
├── assets        — media assets, public-media token delivery
├── jobs          — distributed processing jobs
├── workers       — worker registration and management
├── publishing    — publishing to external platforms
│   └── instagram — real Instagram Graph API integration (Phase 10B)
├── analytics     — analytics and reporting
├── revenue       — revenue tracking and attribution
└── shared        — cross-cutting concerns (web, config, health)
```

As of Phase 10A, `accounts` and `publishing` are implemented (see
[Social accounts and publishing (Phase 10A)](#social-accounts-and-publishing-phase-10a)
below). `robots`, `analytics`, and `revenue` remain placeholders; the intent is
that as each capability is built, its code lands in the matching package with
a clear boundary.

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

## Social accounts and publishing (Phase 10A)

Phase 10A adds the controlled foundation for publishing generated media to
external platforms, without any real Instagram/TikTok integration. Four
concepts stay deliberately separate rather than collapsing into `Job`:

- **`SocialAccount`** (`accounts` package) — a workspace-scoped external
  publishing destination. `SocialPlatform` has `TEST`, `INSTAGRAM`, and
  `TIKTOK` values, but only `TEST` can currently be created or published to;
  the other two are reserved enum values for a future real provider. The
  entity holds only metadata (platform, display name, status) and
  deliberately has **no credential columns**. When a real provider is added in
  Phase 10B+, its access tokens/secrets must live in a separate,
  secret-managed store keyed by the `SocialAccount` id — never on this
  entity, never in a Job payload, and never serialized back to the browser.
  This is a structural boundary (no column exists to misuse), not just a
  convention.
- **`Publication`** (`publishing` package) — a user's durable intent to
  publish one `MediaAsset` to one `SocialAccount`, with an explicit state
  machine: `PENDING -> PUBLISHING -> PUBLISHED` / `FAILED` / `CANCELLED`.
  Publications survive independently of any single Job attempt.
- **`PublishingAttempt`** — per-attempt history for a Publication, one row per
  real worker-reported outcome (`SUCCEEDED`, `RETRYABLE_FAILED`, `FAILED`).
  Rows are never overwritten on retry, so a publication that fails once and
  then succeeds keeps both attempts visible.
- **`PUBLISH_MEDIA` Job** — the existing distributed Job/worker/scheduling
  infrastructure, completely unchanged. Creating a Publication atomically
  creates one `PUBLISH_MEDIA` Job in the same transaction; a retryable
  worker failure requeues that *same* Job row (per the existing `Job.fail`
  semantics), and the *same* Publication is reused across all attempts —
  retries never create Publication2/Publication3. `WorkerSchedulingService`
  and `JobExecutionMetricService` classify `PUBLISH_MEDIA` like other
  asset-linked workloads, so scheduling observability and execution metrics
  work automatically with no scheduler changes.

The eligible asset must be `READY`, `INSPECTED`, and have video
(`hasVideo == true`) — the same gate as the social vertical preset. The
eligible account must be `ACTIVE` and `TEST`. `POST /api/assets/{assetId}/publications`
creates the Publication + Job atomically; `GET /api/publications` (optionally
filtered by `assetId`) and `GET /api/publications/{id}` return the durable
state and attempt history.

On the worker side, `PublishingProvider` (`platform()`, `isAvailable()`,
`publish(mediaFile, authorization)`) is the same kind of provider boundary as
`TranscriptionProvider`. The only implementation, `TestPublishingProvider`, is
deterministic and never contacts any real platform: it validates the
downloaded media is non-empty and (when provided) checksum-matches, then
returns `test-pub-<publicationId>` as the provider publication id. Because
this id is derived only from the Publication id (not a random value or
attempt number), retries of the same Publication are naturally idempotent —
the TEST provider returns the same id every time. The worker only ever
receives a short-lived presigned GET URL for the source asset (via
`ObjectStorageService.presignedGet`) and the Publication id as an idempotency
key; it never receives permanent storage or provider credentials.

The backend stays authoritative over worker-reported results: a completion
request's asset/account/publication ids are cross-checked against the
Publication before anything is persisted, provider ids are length/non-blank
validated, and a nonsensical `publishedAt` timestamp is replaced with the
server's own clock. Stale-worker protection reuses the existing row-locked
`requireJobForWorkerWorkspace` ownership check — a worker whose lease already
expired and was reclaimed cannot mark a Publication `PUBLISHED`. Lease-expiry
recovery mirrors every other domain module: `JobService.recoverExpiredLeases`
requeues the Job and calls a `reconcileRecoveredPublication` hook that moves
the Publication back to `PENDING` (attempts remain) or `FAILED` (attempts
exhausted), implemented directly against `PublicationRepository` — `JobService`
never depends on `PublishingService`, only on its repository, to avoid a
circular bean dependency.

## Instagram publishing (Phase 10B)

Phase 10B adds the first real `PublishingProvider`-equivalent — Instagram —
on top of the Phase 10A foundation above, without redesigning Publication,
PublishingAttempt, PUBLISH_MEDIA, the distributed Job architecture, Worker
ownership, scheduling, execution metrics, asset authorization, or private
MinIO. TEST keeps working exactly as before.

### Official API surface and authentication model

Verified against developers.facebook.com in September 2026, before writing
any provider code. Meta currently supports two OAuth flows for Instagram; the
platform uses **Instagram API with Instagram Login** ("Business Login for
Instagram"), Meta's currently recommended flow for new integrations, because
it needs no linked Facebook Page and no Facebook app review surface — only
Instagram-specific permissions.

- **Supported account types**: Instagram **Business** or **Creator** only.
  Personal accounts cannot use the Content Publishing API at all; a personal
  account must be converted first, entirely outside this app.
- **Scopes**: `instagram_business_basic` and `instagram_business_content_publish`,
  each requiring separate Meta App Review before real accounts outside the
  developer's own can be published to.
- **API version**: `v25.0`, configured via `META_GRAPH_API_VERSION` rather
  than hardcoded, and never influenced by browser input.
- **Authorize URL**: `https://www.instagram.com/oauth/authorize`
- **Code exchange** (server-side only, needs the app secret):
  `POST https://api.instagram.com/oauth/access_token` → short-lived token
  (~1 hour), response shaped as `{"data":[{"access_token":...,"user_id":...}]}`.
- **Long-lived token exchange**: `GET https://graph.instagram.com/access_token?grant_type=ig_exchange_token`
  → 60-day token.
- **Refresh**: `GET https://graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token`,
  valid only once the current token is at least 24 hours old; refreshing
  earlier silently returns the same token. Once a token is fully expired,
  no refresh is possible — the user must reconnect from scratch.
- **Account discovery**: the Instagram Login flow's token exchange itself
  scopes access to exactly the one account the user authorized; this app
  calls `GET /{ig-user-id}?fields=id,username` with `me` as the id to read
  that account's profile. Meta's current docs do not publish a separate
  "list every account this token can access" endpoint for this flow — a
  documented research gap, not a confirmed absence — which is also *why*
  Phase 10B never needed a multi-account selection UI: one OAuth grant
  yields exactly one account.
- **Publishing** (video/Reels only in this phase): create a container
  (`POST /{ig-user-id}/media` with `media_type=REELS`, `video_url`,
  `caption`), poll it (`GET /{container-id}?fields=status_code`, values
  `IN_PROGRESS`/`FINISHED`/`ERROR`/`EXPIRED`/`PUBLISHED`), then publish
  (`POST /{ig-user-id}/media_publish` with `creation_id`).
- **Media URL requirement**: confirmed — Meta's servers fetch the video by
  URL server-side ("we cURL it"); there is no direct-upload alternative for
  this flow. The URL must be publicly reachable.
- **Rate limits**: the content-publishing overview page and the
  `content_publishing_limit` reference page disagree on the numeric quota
  (100 vs 50 posts/24h) — this app never hardcodes either number and instead
  classifies live 4xx throttle responses (error codes 4/17/32/341, or HTTP
  429) as retryable.
- **Revocation**: Meta exposes `DELETE /{user-id}/permissions/{permission}`
  (Graph API generally), but nothing scoped specifically to this flow's
  tokens is documented as callable from here with confidence; disconnect
  therefore removes our own stored credential copy (see below) rather than
  asserting a remote revocation occurred.

### Credential boundary

Phase 10A deliberately shipped `SocialAccount` with no credential columns.
Phase 10B adds exactly the boundary it promised:

- **`social_account_credentials`** (one row per `SocialAccount`, `V15`):
  `encrypted_access_token`, `token_expires_at`, `scopes`, `last_validated_at`.
  Never a plaintext token, never the authorization code (that is exchanged
  and discarded within one request).
- **`CredentialEncryptionService`** — AES-256-GCM, a fresh random 96-bit
  nonce per encryption (so the same token never produces the same ciphertext
  twice), a versioned stored format (`"v1:" + base64(nonce || ciphertext)`),
  and GCM's authentication tag catching any tampering as a hard decryption
  failure rather than corrupted plaintext. The key is read only from
  `SOCIAL_CREDENTIAL_ENCRYPTION_KEY` (base64, 32 bytes); it is never
  generated automatically, never stored in the database, and a blank key
  simply disables the service (`isAvailable() == false`) rather than
  crashing — required so TEST-only deployments never need it. If a key *is*
  supplied but is the wrong length or not valid base64, construction fails
  loudly instead of silently falling back to plaintext.
- **`InstagramStartupCheck`** fails application startup immediately, with a
  clear message, if `app.publishing.instagram.enabled=true` but the
  encryption key or Meta app configuration is missing — so a misconfigured
  Instagram deployment never boots into a half-working state that would
  otherwise be tempted to store credentials in plaintext.
- **`SocialCredentialService`** is the only code path allowed to see
  plaintext: `store` (encrypt immediately), `decryptAccessToken` (used for
  exactly one provider HTTP call, never cached, never logged, never sent
  anywhere except that call), `metadataFor` (a secret-free
  `SocialCredentialMetadata` — presence/expiry/scopes only, safe to show the
  browser), and `remove`. There is no bulk "all plaintext credentials"
  accessor.

### OAuth state, start, and callback

`SocialOAuthState` (`V15`) is a short-lived, single-use, server-tracked CSRF
token: `SocialOAuthStateService.create` generates 32 random bytes, hands the
raw value to the browser inside the authorization URL, and stores only its
SHA-256 hash plus the workspace/user that started the flow and an expiry.
`consume` looks the state up by hash, checks it is unexpired, unused, and for
the right platform, and marks it consumed in the same transaction — so a
replayed callback (same state used twice) is rejected deterministically, not
by a race-prone check-then-act.

`POST /api/social-accounts/instagram/connect` (authenticated, CSRF-protected
like any other mutation) creates that state and returns the Meta
authorization URL as JSON; Angular navigates the browser there itself, so the
POST endpoint never issues a redirect a CSRF token couldn't have protected.
`GET /api/social-accounts/instagram/callback` is the registered Meta
redirect URI. Critically, it **never trusts workspace/user identity from
callback query parameters** — only `code`, `state`, and `error` are read from
the query string; the workspace and user that the connection belongs to come
only from the `SocialOAuthState` row resolved by the state's hash. A forged
or replayed callback cannot act on an arbitrary workspace even if it guesses
a valid-looking state string, because the state itself must already exist,
be unexpired, and be unused. On success or failure the callback ends with an
HTTP redirect to `/settings?instagram=connected` or
`/settings?instagram=error&reason=...` — the authorization code and every
provider token stay entirely server-side; nothing reaches Angular.

### Account identity, duplicates, and disconnect

The persisted `SocialAccount.externalAccountId` is Instagram's own numeric
user id (authoritative), and `displayName` is the provider-reported
`username` (cosmetic, refreshed on reconnect). A partial unique index —
`(workspace_id, platform, external_account_id) WHERE external_account_id IS
NOT NULL` — makes duplicate connections a database-level impossibility for
any platform with a real external identity (TEST accounts, which have no
external identity, are deliberately excluded from this constraint and may
still be created freely for testing). `InstagramAccountConnectionService`
also checks for an existing row with the same external id *before* touching
the database and updates it in place (`SocialAccount.reactivate`) on
reconnect rather than relying on the constraint to reject a duplicate insert.

Disconnect (`POST /api/social-accounts/{id}/disconnect`) is
workspace-authorized, removes the stored credential row, and marks the
account `DISCONNECTED` so `PublishingService.validateAccountEligibility`
refuses new publications against it. It does not delete `Publication`
history. Meta's currently-documented revocation surface is not confirmed
callable for this OAuth flow with confidence (see above), so disconnect is
honestly a **local** credential removal — the user may also need to revoke
access from their own Instagram account settings for a true remote
deauthorization.

### Trust boundary: the Worker never holds a token

Phase 10A's TEST provider runs entirely inside the Worker process. Phase 10B
deliberately does **not** copy that shape for Instagram: doing so would mean
handing a long-lived, real, externally-valid access token to every physical
machine capable of running the worker binary. Instead:

- Every credential-bearing Graph API call (`InstagramGraphClient`) and the
  container-create/poll/publish orchestration (`InstagramPublishingService`,
  the "Instagram Publishing Coordinator") live entirely on the backend.
- The Worker keeps its existing job-ownership, lease-renewal, and polling
  machinery — that infrastructure is unchanged and platform-agnostic — but
  for an Instagram `PUBLISH_MEDIA` job, `PublishMediaExecutor` does not
  download media or hold a token. It repeatedly calls one narrow endpoint,
  `POST /api/worker-agent/publications/{jobId}/instagram/drive`
  (`WorkerToken`-authenticated, job-ownership-checked exactly like every
  other worker-agent endpoint), and the backend decides, one bounded step at
  a time, what actually happens.
- Each `drive` call does at most one of: create a container (if none exists
  yet for this Publication), check container status, or call the final
  publish — and when the outcome becomes terminal (`PUBLISHED` or `FAILED`),
  the **same call** also performs the Job/Publication completion bookkeeping
  that TEST does via separate `/complete`/`/fail` calls. The Worker's loop
  just keeps calling `drive` (renewing the job lease independently, on its
  own thread, exactly as every other job type does) until the status stops
  being `IN_PROGRESS`, then stops — there is nothing left to report.

This means a Worker's technical ability to *drive* Instagram publishing
requires no local tooling at all (unlike FFmpeg/Whisper) — it is pure HTTP
orchestration. To still give operators a meaningful way to restrict which
physical machines can trigger a real, externally-visible side effect (even
though none of them ever see a token), Worker capability advertisement was
generalized exactly the way `ANALYZE_HIGHLIGHTS` already generalizes by
analyzer type:

- `WorkerJobClaimRequest` gained `supportedPublishingProviders`; a Worker
  that doesn't send it (including every pre-Phase-10B binary) defaults to
  `["TEST"]` only — the safe default.
- The Job claim SQL gained a clause exactly mirroring the existing
  `ANALYZE_HIGHLIGHTS` analyzer filter:
  `type <> 'PUBLISH_MEDIA' OR COALESCE(payload ->> 'provider', 'TEST') IN
  (:publishingProviders)`. This is a **hard gate inside the same
  `FOR UPDATE SKIP LOCKED` claim query**, not a soft scoring preference — an
  incompatible Worker's claim query simply never returns an Instagram
  `PUBLISH_MEDIA` row, full stop, before `WorkerSchedulingService` sees it
  at all. `TELEMETRY_AWARE_V1`'s scoring weights are untouched.
  Publication creation now stamps the job payload with
  `"provider": account.getPlatform().name()` so this filter has something to
  match.
- The Java worker only advertises `"INSTAGRAM"` when the operator sets
  `WORKER_INSTAGRAM_PUBLISHING_ENABLED=true` on that specific machine — an
  explicit opt-in, since there is no local capability to auto-detect the way
  FFmpeg/FFprobe presence is auto-detected.

This was verified against a real Postgres instance, not just mocks: a
`PUBLISH_MEDIA` job with `provider: "INSTAGRAM"` in its payload was left
`QUEUED` and untouched by a TEST-only worker actively polling and
successfully claiming other jobs in the same run, and was claimed within one
poll cycle by a second worker started with
`WORKER_INSTAGRAM_PUBLISHING_ENABLED=true`.

### Public media delivery without a public bucket

Meta's servers must fetch the source video from a URL they can reach. The
private MinIO bucket **stays fully private** — no bucket policy change, no
presigned URL handed to Meta. Instead:

- `PublicMediaTokenService` issues a self-contained, HMAC-SHA256-signed token
  (`base64url(publicationId:assetId:expiresAt:nonce) + "." + signature`) —
  not a sequential id, and not forgeable without the same master key used for
  credential encryption (a domain-separated HMAC of the same
  `SOCIAL_CREDENTIAL_ENCRYPTION_KEY`, a MAC rather than AES-GCM so there is
  no cross-protocol key-reuse concern).
- `GET /api/public-media/{token}` is the one intentionally unauthenticated
  endpoint in the API (Meta cannot present a session cookie or CSRF token).
  Every other safety property is enforced by the token and by
  `PublicMediaAccessService`: the token names one specific Publication: the
  storage key actually streamed is always resolved server-side from that
  Publication's own asset row, never accepted as caller input, so there is no
  path here that can read an arbitrary object, list a bucket, or leak a
  storage credential. The endpoint proxies bytes directly from MinIO through
  the backend's own `ObjectStorageService.getObjectStream` — Meta never sees
  a MinIO URL or credential either.
- The token stops working the moment the Publication leaves `PUBLISHING`
  (success, failure, or cancellation) even if it has not technically expired
  yet — a practical, free form of revocation-after-use, and the reason a
  fresh token is issued per container-creation attempt rather than reused.
- `app.publishing.instagram.public-media-url-ttl` bounds how long a token
  stays valid regardless.

**Local development limitation**: Meta's servers cannot reach
`http://localhost:...` or a private LAN MinIO address. A real Instagram
publish therefore requires `META_PUBLIC_BASE_URL` to be a genuine public
HTTPS origin. This is an environment prerequisite, not something the code
can or should work around by weakening storage privacy — see the Phase 10B
final report for how this was handled in this session (Level A acceptance
uses a local fake HTTP server standing in for Meta entirely; Level B real
Meta acceptance is reported separately).

### Reconciliation: avoiding a duplicate real post

Publishing is externally side-effectful, and Instagram's protocol has no
native idempotency key an app can supply for container creation. The
mitigation is `PublicationProviderState` (`V15`, one row per Publication):

- Before any container exists, no row exists. As soon as Meta confirms a
  container id, it is persisted **immediately**, before the Publication
  itself is marked anything — so a retry after a crash or a lost HTTP
  response reuses that same container (`InstagramPublishingService.drive`
  checks for an existing row first) instead of creating a duplicate one.
- Instagram's own container `status_code` can report `PUBLISHED` (not just
  `FINISHED`) once a container has actually been posted — `drive` treats
  that status as "already done" and does **not** call `media_publish` again,
  which is what actually prevents a second real post on retry.
- The one gap the official API leaves, documented rather than hidden: if the
  process crashes in the narrow window between Meta confirming
  `media_publish` succeeded and this service persisting that media id, a
  retry sees the container-level `PUBLISHED` status but has no way to
  recover the exact media id afterward — there is no documented "list
  recent media and match" fallback used here. In that specific case the
  Publication is still completed as `PUBLISHED` (a confirmed real post being
  reported as a false failure would be worse), with `providerPublicationId`
  left `null` rather than guessed. The window this can happen in is as small
  as a single database commit right after the HTTP call returns — this is
  not exactly-once delivery, and is not claimed to be, but it is the
  strongest reconciliation the official API's signals allow.
- Retry classification distinguishes retryable provider failures (5xx,
  network timeouts, rate limiting, a container still processing) from
  terminal ones (expired/invalid token, rejected media, unsupported
  platform) via `InstagramErrorCodes`/`InstagramApiException.retryable()`,
  reusing the exact same `Job`/`Publication` retry semantics Phase 10A
  already established — no second retry system.

### Eligibility

`PublishingEligibilityService` generalizes the Phase 10A
READY+INSPECTED+video gate and adds Instagram-specific checks *before* any
external call is made, using only metadata the existing inspection step
already captured: duration between 3 seconds and 15 minutes, aspect ratio
between 0.1:1 and 10:1, file size under 300 MB, and an MP4/MOV-ish container
— the current officially documented Reels bounds (`ig-user/media` reference,
fetched 2026-09). Requirements this service cannot determine from existing
metadata (exact codec/GOP/bitrate details) are left for Meta's own
validation at container-creation time; Phase 10B never silently transforms
media to force compliance.

## Content drafts (Phase 11A)

`ContentDraft` (`com.fdmultimedia.api.contentdrafts`) is the product bridge
between a source `MediaAsset`, an optional `HighlightCandidate`, and a
`Publication`. It carries `sourceAssetId` (the original/source media,
provenance only) and `mediaAssetId` (the asset currently intended for
publishing, updated as preparation progresses) separately, so a candidate can
flow original → clip → social vertical while the draft always shows both the
original source and the final selected media without duplicating any
MediaAsset. `ContentDraftService` holds a `MediaAssetService` dependency and
calls `createClip`/`createSocialVertical` directly — exactly the reuse
pattern `HighlightService.createClipFromCandidate` already established — so
FFmpeg execution, output-asset creation, and Job creation are never
reimplemented. Publishing likewise goes through a small
`PublishingService.createPublicationForDraft` overload that shares every
eligibility/account/Job-creation rule with the Phase 10A direct-publish path.

### Two creation paths

- **Existing asset → draft** (`POST /api/content-drafts`): the selected
  `MediaAsset` must already be READY, INSPECTED, and video. The draft is
  created directly at `status=READY`, `workflowStage=READY` — no processing
  needed, `sourceAssetId == mediaAssetId`.
- **Highlight candidate → draft** (`POST /api/content-drafts/from-highlight/{candidateId}`):
  requires a `SUCCEEDED` `HighlightAnalysis`. The endpoint synchronously calls
  `MediaAssetService.createClip` (a DB insert + Job creation, not FFmpeg
  itself — the same thing `POST /api/highlight-candidates/{id}/create-clip`
  already does) and creates the draft at `status=DRAFT`,
  `workflowStage=CLIP_PENDING`, pointing `mediaAssetId` at the in-progress
  clip. A candidate-originated draft always targets the 9:16 social vertical
  as its final asset, matching the short-form publishing target.

### Durable, idempotent workflow reconciliation

`workflow_stage` (`CLIP_PENDING` → `VERTICAL_PENDING` → `READY`) and
`pending_job_id` live as plain columns on `content_drafts` — no second
workflow-state table, no in-memory callback. `ContentDraftService.reconcile`
runs inside every single-draft read (`GET /api/content-drafts/{id}`, and once
per draft on `GET /api/content-drafts`), fetching the row with
`ContentDraftRepository.findByWorkspaceAndIdForUpdate`
(`SELECT ... FOR UPDATE`) before inspecting it:

- If the pending derivative's own `MediaAsset` has failed, the draft moves to
  `FAILED` with a copy of the derivative's error — no exception escapes to
  the caller.
- If it has reached READY+INSPECTED, the draft advances exactly one stage
  (`CLIP_PENDING` → calls `createSocialVertical` once and moves to
  `VERTICAL_PENDING`; `VERTICAL_PENDING` → moves to `READY`).
- Otherwise the read is a no-op.

The row lock plus the stage field itself as the idempotency guard means
concurrent or repeated reads/polls of the same draft cannot both observe
`CLIP_PENDING` and each queue a `CREATE_SOCIAL_VERTICAL` Job — the first
reconciliation to commit advances the stage, and every later read sees the
new stage and does nothing further. Because reconciliation reads only
persisted `MediaAsset`/`Job` state, a preparing draft survives an API
restart, a Worker restart, or a Job retry exactly like any other
Job-observing read in this codebase — nothing depends on which process or
Worker instance happened to run the derivative.

User-triggered retry (`POST /api/content-drafts/{id}/retry-preparation`,
only valid from `FAILED`) re-derives the failed stage rather than replaying a
generic retry: a failed clip re-runs `createClip` from the original candidate
timing; a failed vertical re-runs `createSocialVertical` from the failed
asset's own `parentAsset` (the successful clip), located through the same
`MediaAsset` derivation lineage Phase 7/8 already established. Each retry
click creates exactly one new Job — never an automatic retry loop.

### Publication linkage and caption snapshot

`publications.content_draft_id` is a plain nullable UUID column (not a JPA
relationship) so the `publishing` package never has to depend on
`contentdrafts` — only `contentdrafts` depends on `publishing`, avoiding a
package cycle. It is intentionally not a required one-to-one link: a draft
may accumulate more than one `Publication` over time (a failed attempt
followed by a successful retry, or, when a platform beyond TEST/Instagram
exists, one Publication per platform), and `Publication` remains the
unedited historical record of exactly what was submitted. `ContentDraft.caption`
is the editable, in-progress product state; the moment
`createPublicationForDraft` runs, the current caption is copied into the new
`Publication` row, and every later edit to the draft's caption has no way to
reach that row again — there is no back-reference, only the one-time copy at
construction.

Draft `status` is *derived* from its linked Publications on every
reconciled read, never treated as an independent source of truth once a
Publication exists: any `PENDING`/`PUBLISHING` Publication shows the draft as
`PUBLISHING`; any `PUBLISHED` Publication shows it as `PUBLISHED` (and copies
its `publishedAt`); if every Publication has reached a terminal failure, the
draft reverts to `READY` rather than staying stuck — a failed publish must
never make a draft permanently unusable. Publishing again from `READY` or
`PUBLISHED` creates a new `Publication` (never mutates a prior one), which is
how a draft becomes reusable after a failure and how the schema already
supports a future multi-platform fan-out without redesigning `ContentDraft`.

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
