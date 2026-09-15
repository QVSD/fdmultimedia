# Architecture

## Phase 7B1 scope

Phase 7B1 adds persisted speech transcripts on top of the distributed job
pipeline. Authenticated users can submit direct HTTP/HTTPS media file URLs;
the control plane creates a `MediaAsset` plus an `IMPORT_MEDIA` job, and a
worker safely downloads, validates, checksums, uploads, and completes the
import. Once the asset is READY, the API creates an `INSPECT_MEDIA` job so an
FFprobe-capable worker can inspect the stored original. READY + INSPECTED
assets can then be used as immutable sources for `CREATE_CLIP`, which creates
new clip assets, or `CREATE_SOCIAL_VERTICAL`, which creates 1080x1920
center-cropped derivatives. Phase 7A can also create `ANALYZE_HIGHLIGHTS`
jobs that persist structured candidate intervals. The current analyzer is
deterministic and local only; AI/provider integration, publishing, smart
scheduling, and social integrations remain out of scope; see
[ROADMAP.md](ROADMAP.md).
Phase 7B1 does not replace that deterministic analyzer. Instead it adds
`TRANSCRIBE_MEDIA`, `MediaTranscript`, and `TranscriptSegment` so transcript
data survives independently for later semantic highlight selection, subtitles,
search, summaries, chapters, and podcast workflows.

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
  dispatch optimization. Phase 6A intentionally continues to use PostgreSQL row locking
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

Claims set `lease_expires_at` and increment `attempt_count`. Starting and
renewing a job refreshes the lease. The worker renews leases while long-running
imports are active, so legitimate downloads do not look abandoned. If a laptop
disappears after claim/start and stops renewing, the next claim for that
workspace lazily recovers expired active jobs: retryable jobs return to
`QUEUED`, while jobs that exhausted `max_attempts` become `FAILED`. This is
deliberately simple and avoids a distributed scheduler in Phase 6A.

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
