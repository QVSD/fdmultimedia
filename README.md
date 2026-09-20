# fd multimedia

A distributed media automation platform: eventually this will manage
social-media content workflows, video processing workers running across
multiple laptops/cloud machines, scheduling, AI-assisted content creation,
publishing, analytics, and revenue tracking.

## Phase 13B scope

This repository is currently at **Phase 13B: Publication Analytics Dashboard**.
Phase 13A gave published TEST content deterministic, historical analytics
snapshots and a publication-time provenance snapshot; analytics observes
publications and never changes Robots, Personas, captions, schedules, or AI
policies. Instagram analytics remains permission-gated pending verified
insights scopes. Phase 13B adds read-only comparison, trend, and breakdown
views over that same data — date-range/window/provider/Robot/Persona/
ContentSource/origin/AI-usage filtering, explicit coverage reporting so a low
sample count is never hidden inside an average, and bounded breakdowns — with
no ranking, scoring, or automatic optimization.

## Earlier phases
Phase 9A introduced current Worker telemetry and per-attempt execution metrics;
Phase 9B introduced deterministic `TELEMETRY_AWARE_V1` selection; Phase 9C
persisted successful claim decisions and exposed bounded workspace-scoped
queue, execution, Worker/job-type, fallback, and starvation statistics. Phase
10A added the secure domain model and distributed publishing pipeline
foundation for eventually publishing media to Instagram/TikTok — a
workspace-scoped `SocialAccount`, a `Publication` state machine with durable
per-attempt history, and a new `PUBLISH_MEDIA` job type proven end-to-end by
a deterministic, **explicitly non-real** `TEST` publishing provider. Phase
10B now connects the first **real** provider: Instagram, using only Meta's
official "Instagram API with Instagram Login" and Content Publishing API
(video/Reels), disabled by default and requiring explicit Meta app
configuration plus an AES-256-GCM credential encryption key to enable. TEST
keeps working unchanged either way. Phase 11A adds `ContentDraft`, turning
the separate clip/vertical/highlight/publish actions into one coherent
content workflow: a draft can be created from an existing eligible asset or
from a highlight candidate (which reuses the existing `CREATE_CLIP` /
`CREATE_SOCIAL_VERTICAL` pipeline, never a second media-processing system),
tracks durable preparation progress that survives polling and restarts, and
publishes through the same `PublishingService` used since Phase 10A. Phase
11B adds `PublishSchedule`: a user picks a future date/time (converted from
local browser time to an unambiguous UTC instant, never a naive
timezone-less string) and a destination account for a READY Draft; the
media, caption, and account are snapshotted at that moment so a later Draft
edit can never silently change an already-scheduled post. Nothing is
reserved before the due time — no Worker, no Job, no Publication — the
central server's own `@Scheduled` dispatcher claims due schedules with the
same atomic `FOR UPDATE SKIP LOCKED` pattern the Job queue already uses, and
turns each one into a normal Publication through the existing
`PublishingService`. A Worker being offline at due time just means the
resulting `PUBLISH_MEDIA` Job sits queued normally until one claims it.
Phase 11C adds `Robot`: a persistent automation *policy*, not a Worker and
not a Job — it orchestrates the same highlight/draft/schedule services a
human uses from the Content page. Three autonomy modes bound how far a
Robot may act unattended: `DRAFT_ONLY` stops at a READY draft,
`REVIEW_REQUIRED` waits for a human `RobotApproval` decision, and
`AUTO_SCHEDULE` creates a `PublishSchedule` with no human step — but the
backend hard-rejects `AUTO_SCHEDULE` against any real provider (Instagram),
so unattended posting to a real account is impossible regardless of
configuration. A durable `RobotRun` audit record and a central
`FOR UPDATE SKIP LOCKED` poller mean no in-memory pipeline or waiting
thread: a restart mid-run loses nothing. Phase 11D adds `ContentSource`: a
controlled, workspace-scoped pool of a user's own imported media a Robot
may select the next video from, instead of always pointing at one fixed
asset — never a URL feed, RSS, or any form of autonomous web access. A
Robot's `selectionPolicy` (`OLDEST_UNPROCESSED`/`NEWEST_UNPROCESSED`) is
deterministic and explainable, never AI ranking; one PostgreSQL query owns
eligibility, ordering, and per-Robot exclusion of every asset that Robot has
already selected before (even from a run that later failed), with a
database-level unique index as defense in depth so the same Robot can never
select the same asset twice while a different Robot sharing the source
freely may. Phase 12A adds `ContentSuggestion`: a human can generate an
AI-drafted hook/caption/hashtags for a READY `ContentDraft`, always as a
reviewable suggestion — AI output can never silently mutate a Draft,
publish, create a `PublishSchedule`, or bypass Robot approval, and Robots do
not auto-generate suggestions in this phase. Generation runs as a
`GENERATE_SOCIAL_COPY` Job through the same distributed Job/Worker
infrastructure every other job type uses, behind a narrow
`ContentEnrichmentProvider` abstraction with a `DETERMINISTIC_TEST` provider
for tests/local fallback and a real local Ollama provider for actual LLM
generation — no domain code depends on a vendor SDK directly. A deterministic
input fingerprint detects when a human has edited the Draft since
generation, rejecting a stale Apply instead of silently overwriting the
edit; regenerating always creates a new, independent suggestion, never
overwriting history. Phase 12B adds `Persona`: reusable, workspace-scoped
editorial configuration (audience/voice/style/avoid/hashtag-guidance/
example-copy, each field explicitly bounded, never a raw prompt) a human
may optionally attach to a generation request — never a Robot, provider, or
social account. A generation request's resolved language/tone follow an
explicit precedence (request value, then Persona default, then the Phase
12A global default), and the selected Persona's editorial fields are copied
once into an immutable snapshot stored directly on the resulting
`ContentSuggestion`; later editing or archiving that Persona can never
change an existing suggestion's history, its displayed name, or cause a
previously-valid Apply to start failing — only an actual Draft edit still
does that. Archiving a Persona only blocks it from *new* generation.
Persona data reaches the Worker exactly the way the rest of the prompt
already did in Phase 12A — as text inside the same opaque, frozen prompt —
so no Worker/Job architecture changed at all. Phase 12C connects Robots
(11C/11D) to AI generation (12A/12B) for the first time, through a new
`RobotAiPolicy` (`NO_AI`/`GENERATE_FOR_REVIEW`/`GENERATE_AND_APPLY`) kept
strictly independent of the existing `RobotAutonomyMode` axis. `NO_AI`
reproduces prior behavior exactly. `GENERATE_FOR_REVIEW` creates exactly
one automatic `ContentSuggestion` and parks the `RobotRun` in a new
`WAITING_FOR_AI_REVIEW` state — deliberately distinct from the pre-existing
publishing-approval `WAITING_FOR_REVIEW` state — until a human Applies or
Discards it, after which the Robot resumes unattended.
`GENERATE_AND_APPLY` auto-applies through the exact same human Apply path
(fingerprint/staleness checks included) before continuing. A Robot may
optionally reference a Persona, validated `ACTIVE` at both configure time
and generation time; `RobotRun` snapshots the AI policy and Persona
*identity* at run-creation time so a mid-run Robot edit can never redirect
an in-flight run. Every automatic suggestion carries an explicit
`origin=ROBOT` and its originating `robotRunId` — no longer inferred from
the Draft's own Robot provenance, a latent conflation this phase fixed.
Reconciliation reuses Phase 11C's existing row-locked bounded poller
unchanged, giving idempotent, multi-instance-safe progress (at most one
automatic suggestion and one Job per run) with zero new locking code.
`AUTO_SCHEDULE`'s real-provider gate (TEST only) is unaffected by AI
policy.

Claim decisions are written in the same transaction as assignment. Empty polls
and capacity rejections are not persisted, preventing poll-noise growth. Decision
history defaults to a 24-hour query window, supports `1h`, `24h`, `7d`, and `30d`,
and is retained for 30 days by default.
That means:

- A clean monorepo layout (`apps/`, `workers/`, `infra/`, `docs/`).
- A Spring Boot 21 modular monolith (`apps/api-spring`) with the package
  structure for future modules, a health endpoint, PostgreSQL + Flyway,
  RabbitMQ connectivity, and secure session-based authentication.
- Users, workspaces, and workspace memberships with OWNER/ADMIN/MEMBER
  roles. Future business resources can be scoped to `workspace_id`.
- Worker credentials, worker registration, heartbeat tracking, current
  capability snapshots, lightweight telemetry, and a workspace-scoped Compute
  page. Worker status is derived from heartbeat age; scheduling state is
  derived from fresh capacity telemetry.
- Centrally-created `SYSTEM_TEST`, `IMPORT_MEDIA`, `INSPECT_MEDIA`,
  `CREATE_CLIP`, `CREATE_SOCIAL_VERTICAL`, and `ANALYZE_HIGHLIGHTS` jobs
  with PostgreSQL-backed durable state, atomic worker claiming, leases,
  bounded retry, and result/error tracking.
- A standalone Java 21 worker agent in `workers/java-agent` that persists a
  random installation identifier locally, reports basic machine metadata,
  heartbeats, polls for work, renews long-running leases, executes safe
  `SYSTEM_TEST` jobs, imports direct HTTP/HTTPS media files, inspects stored
  originals read-only when FFprobe is available, and creates controlled clip
  and 9:16 social vertical derivatives when FFmpeg is available.
- Media assets backed by private S3-compatible object storage. Local
  development uses MinIO with a private `media-assets` bucket.
- An Angular application (`apps/web-angular`) with a login page, protected
  dashboard routes, a sidebar shell, a Compute page, a Jobs page, and a
  functional Content page for direct media imports, simple clip creation, and
  a fixed vertical 9:16 preset.
- Persisted highlight analyses and candidate recommendations. Phase 7A uses a
  deterministic local analyzer (`DETERMINISTIC_V1`) only; it does not call
  OpenAI, Anthropic, Gemini, a local LLM, or vision models.
  Users can review candidates and explicitly create clips through the existing
  `CREATE_CLIP` pipeline.
- First-class media transcripts. A READY + INSPECTED asset with audio can
  create a `TRANSCRIBE_MEDIA` job, producing a persisted `MediaTranscript`
  plus timestamped `TranscriptSegment` rows. The transcript survives
  independently from highlight analysis and is ready for later subtitles,
  search, summaries, chapters, and semantic highlight selection.
- Nginx as the single entry point, routing `/api/*` to the backend and
  everything else to the frontend.
- Docker Compose to run the whole stack locally.
- Scheduler-oriented execution metrics are recorded from server timestamps for
  completed, failed, and lease-recovered attempts. Phase 9B uses those metrics,
  fresh active-job capacity, CPU/memory telemetry when available, and
  starvation protection to choose among locked compatible queued jobs while
  preserving PostgreSQL `FOR UPDATE SKIP LOCKED` claim safety.
- Workspace-scoped `SocialAccount`s (`GET/POST /api/social-accounts`), with
  only the deterministic, non-real `TEST` platform enabled today.
  `INSTAGRAM`/`TIKTOK` are reserved enum values with no working provider.
- `Publication`s (`POST /api/assets/{assetId}/publications`,
  `GET /api/publications`) with an explicit PENDING → PUBLISHING →
  PUBLISHED/FAILED/CANCELLED state machine and durable per-attempt
  `PublishingAttempt` history, backed by a new `PUBLISH_MEDIA` job type that
  reuses the existing distributed Job infrastructure unchanged. A Settings
  page manages TEST and Instagram accounts; the Content page can publish
  eligible READY + INSPECTED video assets and shows publication state, with
  TEST always clearly labeled as non-real.
- Real Instagram account connection via official OAuth
  (`POST /api/social-accounts/instagram/connect`,
  `GET /api/social-accounts/instagram/callback`), an encrypted server-side
  credential store (never sent to the browser or to a Worker), and real
  Reel publishing driven by a bounded backend orchestrator
  (`InstagramPublishingService`) that a Worker only polls — see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#instagram-publishing-phase-10b)
  for the full design, including the credential trust boundary, provider-aware
  Worker capability gating, the unauthenticated-but-token-scoped public media
  delivery endpoint, and how duplicate real posts are avoided on retry.
  Disabled and fully optional: the app starts and TEST publishing works with
  none of this configured.
- Workspace-scoped `ContentDraft`s (`GET/POST /api/content-drafts`,
  `GET/PATCH /api/content-drafts/{id}`,
  `POST /api/content-drafts/from-highlight/{candidateId}`,
  `POST /api/content-drafts/{id}/publish`,
  `POST /api/content-drafts/{id}/retry-preparation`) bridging a source
  `MediaAsset`/`HighlightCandidate` to a `Publication` as one editable
  product object, with a durable `DRAFT`/`READY`/`PUBLISHING`/`PUBLISHED`/`FAILED`
  status and idempotent workflow reconciliation — see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#content-drafts-phase-11a) for
  the full design, including the caption-snapshot rule and why a failed
  Publication reverts a draft to READY instead of destroying it. The Content
  page has a Drafts view alongside Assets, with "Create Draft" actions on
  eligible assets and highlight candidates; the Phase 10A direct-publish flow
  still works unchanged.
- Workspace-scoped `PublishSchedule`s
  (`POST /api/content-drafts/{draftId}/schedules`,
  `GET /api/publish-schedules`, `GET /api/publish-schedules/{id}`,
  `GET /api/publish-schedules/calendar`,
  `POST /api/publish-schedules/{id}/cancel`,
  `PATCH /api/publish-schedules/{id}`) let a user schedule a READY Draft for
  a future instant, with media/caption/account snapshotted at creation — see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#content-publishing-schedule-phase-11b)
  for the full design, including why nothing is reserved before due time, the
  atomic `FOR UPDATE SKIP LOCKED` dispatcher (proven against two concurrent
  Postgres sessions), and offline-Worker/offline-server misfire semantics.
  The Content page has a Schedule tab (Upcoming/History) and a Schedule
  action on READY/PUBLISHED Drafts, alongside the existing immediate Publish.
- Workspace-scoped `Robot`s (`GET/POST /api/robots`, `GET/PATCH
  /api/robots/{id}`, `POST /api/robots/{id}/run|pause|resume`,
  `GET /api/robots/{id}/runs`, `GET/POST /api/robot-runs*`,
  `GET/POST /api/robot-approvals*`) — a persistent automation policy that
  reuses `HighlightService`, `ContentDraftService`, and
  `PublishScheduleService` unchanged; see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#robots--automation-foundation-phase-11c)
  for the full design, including the three autonomy modes, the
  backend-enforced real-provider safety boundary (`AUTO_SCHEDULE` can never
  target Instagram or any other real provider), the durable `RobotRun`
  reconciliation model, workload limits, duplicate-source protection, and
  the Pause/`ROBOT_AUTOMATION_ENABLED` kill switches. The Content page's
  Provenance panel shows when a draft was created by a Robot run, and a new
  Robots page lets a user create Robots, run them on demand, and
  approve/reject `REVIEW_REQUIRED` proposals.
- Workspace-scoped `ContentSource`s (`GET/POST /api/content-sources`,
  `GET/PATCH /api/content-sources/{id}`,
  `POST /api/content-sources/{id}/pause|resume`,
  `GET/POST /api/content-sources/{id}/assets`,
  `DELETE /api/content-sources/{id}/assets/{assetId}`) — a controlled pool
  of a workspace's own imported media a Robot may select the next video
  from; see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#dynamic-content-sources--selection-policies-phase-11d)
  for the full design, including why only `ORIGINAL` assets can join a
  source, the deterministic `OLDEST_UNPROCESSED`/`NEWEST_UNPROCESSED`
  selection policies and their one-query eligibility/ordering/exclusion SQL,
  the database-level duplicate-consumption guard, and why an empty-but-active
  source produces an auditable `NO_ELIGIBLE_SOURCE` run while a paused one
  rejects before any run exists. A Robot's `sourcePolicy` is now either the
  unchanged Phase 11C `EXISTING_ASSET` or the new `CONTENT_SOURCE`. The
  Content page gained a Sources tab and an "Add to Source" action on
  original assets.

No thumbnails, TikTok/YouTube/other platform integrations, analytics-driven
optimization, or billing are implemented yet — see
[docs/ROADMAP.md](docs/ROADMAP.md) for what comes next and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit
together.

## Prerequisites

- [Docker](https://www.docker.com/) with Docker Compose v2 (`docker compose`,
  not the old `docker-compose`).
- That's it for running the stack. For working on the apps directly
  outside Docker, see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
  (Java 21 + Maven for the backend, Node.js 22+ for the frontend).

## Configure

```bash
cp .env.example .env
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Edit `.env` if you want different local ports or credentials — the
defaults are safe, development-only values. Never commit a real `.env`
file.

## Start the stack

```bash
docker compose up --build
```

This builds and starts, in dependency order:

1. `postgres` — PostgreSQL, with a named volume for data.
2. `rabbitmq` — RabbitMQ, with a named volume for data.
3. `minio` — private S3-compatible object storage for imported media.
4. `api` — the Spring Boot backend (waits for Postgres, RabbitMQ, and MinIO to be
   healthy, then runs Flyway migrations on startup).
5. `web` — the Angular frontend, built and served as static files.
6. `nginx` — the reverse proxy in front of `api` and `web`.

Once everything is healthy, open:

**http://localhost:8080** (or whatever `NGINX_PORT` you set in `.env`)

You should be redirected to `/login`. Sign in with the bootstrap credentials
from your local `.env`, then you should see the dashboard with **Backend:
Online**, your user, workspace, and role.

Run it in the background instead with `docker compose up --build -d`, and
follow logs with `docker compose logs -f`.

## Stop the stack

```bash
docker compose down
```

## Reset local data (Postgres / RabbitMQ / MinIO volumes)

```bash
docker compose down -v
```

This deletes the named volumes (`postgres_data`, `rabbitmq_data`,
`minio_data`), so the next `docker compose up` starts from a clean database,
message broker, and object store.

## Backend health check

- Application health endpoint: `GET http://localhost:8080/api/health`
  → `{"status":"UP","service":"media-platform-api"}`
- Spring Boot Actuator health endpoint:
  `GET http://localhost:8080/api/actuator/health`

(Both are reached through Nginx at the port above; the backend itself is
not exposed directly to the host.) Actuator dependency details are hidden by
default and in `prod`; the `dev` profile exposes them for local diagnostics.

## Authentication

- `POST /api/auth/login` starts a secure server-side session.
- `GET /api/auth/me` returns the current safe user/workspace context.
- `POST /api/auth/logout` invalidates the session and requires CSRF.
- `GET /api/workspaces` and `GET /api/workspaces/{workspaceId}` require an
  authenticated workspace member.

The development profile can bootstrap one OWNER and one optional ADMIN in the
same workspace from `.env`. Restarting the stack is idempotent and does not
create duplicates.

## Worker registration

The development profile can also bootstrap one worker credential from `.env`.
The API stores only a BCrypt hash of the worker secret. A worker agent uses a
machine token in this format:

```text
FDM_WORKER_TOKEN=<BOOTSTRAP_WORKER_CREDENTIAL_ID>.<BOOTSTRAP_WORKER_CREDENTIAL_SECRET>
```

Build and run the local Java worker agent:

```bash
cd workers/java-agent
../../apps/api-spring/mvnw -f pom.xml package
FDM_API_BASE_URL=http://localhost:8080/api \
FDM_WORKER_TOKEN=11111111-1111-4111-8111-111111111111.dev_worker_secret_change_me \
java -jar target/worker-agent-0.1.0-SNAPSHOT.jar
```

On Windows PowerShell, run Maven through `apps\api-spring\mvnw.cmd` and set
the same environment variables with `$env:FDM_API_BASE_URL` and
`$env:FDM_WORKER_TOKEN`. Install FFmpeg/FFprobe and set `$env:FFPROBE_PATH`
if `ffprobe` is not on `PATH`; set `$env:FFMPEG_PATH` if `ffmpeg` is not on
`PATH`. Without FFprobe the worker still registers and imports media, but it
does not advertise `INSPECT_MEDIA`. Without FFmpeg it does not advertise
`CREATE_CLIP` or `CREATE_SOCIAL_VERTICAL`. For transcription, install a local
Whisper-compatible CLI explicitly and set `$env:TRANSCRIPTION_RUNTIME`,
`$env:TRANSCRIPTION_COMMAND`, and `$env:TRANSCRIPTION_MODEL`. The default
runtime is `WHISPER_CLI` for the Python `whisper` CLI contract. Use
`WHISPER_CPP` with `whisper-cli` and a local `ggml-*.bin` model path. Without a
working transcription CLI/model, the worker still starts but does not advertise
`TRANSCRIBE_MEDIA`.

Worker heartbeats also report cheap dynamic telemetry when available:

- system CPU load and process CPU load as `0..1`
- available system memory
- JVM heap used/max
- active jobs currently executing on that worker
- current capability snapshot

Unavailable or invalid values are omitted/null. Telemetry is operational data
only; it is not used for authorization. `WORKER_MAX_ACTIVE_JOBS` defaults to
`1`; the Java worker avoids claim polling while it is locally at capacity, and
the API enforces fresh reported capacity during scheduling.

## Jobs, media assets, and distributed execution

Phase 4 uses PostgreSQL as the durable job queue. Workers poll the control
plane and claim jobs with a transactional `FOR UPDATE SKIP LOCKED` query, so
one queued job is assigned to exactly one worker. RabbitMQ remains available
in the stack but is reserved for a later event-driven dispatch optimization.
Phase 9B keeps this atomic claim path intact. Eligibility is isolated behind a
server-side boundary that answers which online workers can run a job based on
reported capabilities and analyzer support. Scheduling policy
`TELEMETRY_AWARE_V1` then scores the locked compatible candidate window for the
polling worker using capacity, fresh telemetry, bounded recent execution
history, deterministic tie-breaking, and starvation protection. Missing/stale
telemetry degrades to FIFO.

The first executable job type is `SYSTEM_TEST`. It accepts:

```json
{
  "message": "hello worker",
  "durationMs": 2000
}
```

The worker validates this payload, optionally waits for `durationMs`, and
returns a JSON result with the message, worker name, and execution duration.
It never executes shell commands or arbitrary code.

Job states are:

```text
QUEUED -> ASSIGNED -> RUNNING -> SUCCEEDED
QUEUED -> CANCELLED
ASSIGNED/RUNNING -> QUEUED     (retry after failure or expired lease)
ASSIGNED/RUNNING -> FAILED     (max attempts exhausted)
```

`SUCCEEDED`, `FAILED`, and `CANCELLED` are terminal. Active jobs have a
lease; if a worker disappears, the next claim operation recovers expired
leases and either requeues the job or marks it `FAILED` when attempts are
exhausted.

For scheduler preparation, the API records one execution metric per attempt
when an attempt succeeds, fails, or is recovered after lease expiry:

- `queueWaitMs = assignedAt - queuedAt`
- `executionMs = finishedAt - startedAt` when the worker acknowledged start
- `totalLatencyMs = finishedAt - queuedAt`
- job type, worker, attempt, outcome, and safe workload hints such as media
  size/duration/dimensions or provider/model/analyzer identifiers when those
  already exist in platform data

The history is intentionally lightweight and is not a high-volume telemetry
time series.

Phase 5 adds `IMPORT_MEDIA`. A user submits a direct HTTP/HTTPS media file URL
from Content. The API derives the workspace from the session, creates a
`MediaAsset` in `PENDING`, creates an `IMPORT_MEDIA` job with only the asset ID
in its payload, and links them transactionally.

Asset states are:

```text
PENDING -> IMPORTING -> READY
PENDING/IMPORTING -> FAILED
IMPORTING -> PENDING      (retryable worker failure)
```

`READY` means the original media was successfully stored in private object
storage and has file size, SHA-256 checksum, content type, and basic metadata
recorded. The server generates storage keys like
`workspaces/{workspaceId}/assets/{assetId}/original`; user filenames never
control object paths.

Workers do not receive permanent MinIO/S3 credentials. For an import, the
worker requests short-lived upload authorization from the API, downloads the
validated source URL to a temporary file with size and timeout limits, computes
SHA-256, uploads with a presigned PUT URL, then reports completion. Authorized
browser users can request a short-lived presigned GET URL for READY assets via
`GET /api/assets/{id}/download-url`; the bucket is not public and signed URLs
are never stored in Postgres.

URL validation is intentionally strict. Only `http` and `https` are accepted.
Embedded credentials, localhost, loopback, private/link-local/multicast/reserved
addresses, and cloud metadata endpoints are blocked. Redirect targets are
revalidated by the worker. Downloads are streamed to disk, capped by
`MEDIA_MAX_DOWNLOAD_SIZE_BYTES`, and temporary files are deleted on success or
failure. Clearly non-media responses such as HTML, JSON, XML, and text are
rejected.

Phase 6A adds read-only media inspection with `INSPECT_MEDIA`. When an import
completes successfully, the API creates one inspection job for the stored
asset. Workers advertise `INSPECT_MEDIA` only after `ffprobe -version`
succeeds, then download the private original through a short-lived presigned
GET URL and run FFprobe with fixed arguments:

```text
ffprobe -v error -print_format json -show_format -show_streams <file>
```

The worker uses `ProcessBuilder` without a shell, does not accept arbitrary
FFprobe arguments, and does not transform media. Inspection stores duration,
resolution, video codec, audio codec, container format, frame rate, bitrate,
and `hasVideo`/`hasAudio` flags when FFprobe can determine them. Attached
picture streams are ignored for primary video selection. Inspection failures
set the inspection state to failed but do not change a READY asset back to a
failed import state.

Phase 6B adds controlled clip derivatives with `CREATE_CLIP`. A user can create
a clip only from a `READY` + `INSPECTED` source asset. The API validates
`startMs >= 0`, `durationMs > 0`, and, when duration is known, requires the
requested interval `[startMs, startMs + durationMs)` to fit inside the source.
The original object is immutable: the API creates a new derived `MediaAsset`
with `parentAssetId` pointing to the source and `derivationType=CLIP`, then a
`CREATE_CLIP` job with the source/output IDs and timing parameters.

FFmpeg-capable workers request a presigned GET URL for the source and a
presigned PUT URL for the derived output. The worker downloads the source with
the same bounded streaming pattern used for inspection, runs FFmpeg through
`ProcessBuilder` with fixed arguments and no shell, uploads the new MP4,
reports checksum/size, and deletes both temp files. The server owns the output
storage key (`workspaces/{workspaceId}/assets/{derivedAssetId}/original`) and
marks the derived asset `READY`, then automatically creates `INSPECT_MEDIA` so
the derivative can become `INSPECTED`. FFmpeg uses H.264 video, AAC audio, MP4
container, `-ss` after `-i` for more accurate first-pass timing, and controlled
arguments only; users cannot submit raw FFmpeg options.

Phase 6C adds `CREATE_SOCIAL_VERTICAL`, the first fixed social-media transform
preset. A user can choose any `READY` + `INSPECTED` asset that contains video
and request `SOCIAL_VERTICAL`. The selected asset is the immediate parent, so
both `ORIGINAL -> SOCIAL_VERTICAL` and `ORIGINAL -> CLIP -> SOCIAL_VERTICAL`
lineage are valid. The source object remains immutable.

The server creates a new derived `MediaAsset` with
`derivationType=SOCIAL_VERTICAL`, queues a `CREATE_SOCIAL_VERTICAL` job, and
uses the same private presigned GET/PUT storage flow as clips. FFmpeg-capable
workers use a fixed, internally constructed center-crop filter:

```text
scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920
```

The output is MP4, exactly 1080x1920, H.264 video, and AAC audio when the
source has audio. Video-only sources produce valid video-only MP4s. The preset
preserves timeline duration within normal encoding/container tolerance, does
not letterbox, does not stretch, and does not use subject-aware or AI reframing
yet. The derivative becomes `READY`, then the existing automatic
`INSPECT_MEDIA` chain verifies the stored output.

MinIO console is exposed for local development at **http://localhost:9001** (or
`MINIO_CONSOLE_PORT`) using `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` from
`.env`.

Phase 7A adds `ANALYZE_HIGHLIGHTS`. A READY + INSPECTED video asset with known
duration can be analyzed from the Content page. The API creates a
`HighlightAnalysis` row in `PENDING`, queues one `ANALYZE_HIGHLIGHTS` job, and
the worker completes it using a deterministic analyzer that proposes bounded
candidate intervals around fixed timeline positions. Candidates are persisted
as first-class `HighlightCandidate` rows with start/end, score, reason, and
rank.

The analyzer output is treated as untrusted by the backend. The server enforces
workspace scope, asset readiness, `hasVideo`, known duration, max candidate
count, candidate duration limits, score range, reason length, and deterministic
rank ordering. A candidate is only a recommendation; no media is generated
until a user clicks **Create Clip**, which reuses the existing clip API and
therefore the same FFmpeg, storage, retry, inspection, and lineage flow.

Phase 7B1 adds `TRANSCRIBE_MEDIA`. The API derives workspace access from the
session, requires the asset to be READY, INSPECTED, to contain audio, and to
have known duration, then creates or reuses the active transcript for the same
asset/provider/model. The worker gets only a short-lived presigned GET URL for
the assigned asset, extracts mono 16 kHz PCM audio with controlled FFmpeg
arguments, runs a configured local Whisper-compatible CLI, and submits
structured `{language, segments[]}` output. The backend validates segment
timestamps, text length, count, total size, ordering, and duration bounds before
atomically persisting segments and marking the transcript `SUCCEEDED`.
Set `APP_TRANSCRIPTION_PROVIDER` and `APP_TRANSCRIPTION_MODEL` for the API so
the persisted transcript provider/model match the runtime advertised by local
workers.

Phase 7B2 adds semantic highlight analysis behind the existing
`ANALYZE_HIGHLIGHTS` job type. `DETERMINISTIC_V1` remains the default analyzer;
clients may explicitly request `TRANSCRIPT_SEMANTIC_V1` for READY + INSPECTED
video assets that have a successful transcript. Semantic workers advertise the
analyzer only when a configured local provider is available. The first provider
uses a local Ollama HTTP runtime and sends transcript windows only: no media
objects, presigned URLs, storage keys, credentials, raw commands, or browser
input are sent to the model. The backend validates that returned candidates are
grounded in transcript segment windows before persisting them.

Useful local semantic-highlight worker settings:

```text
SEMANTIC_HIGHLIGHT_RUNTIME=OLLAMA
SEMANTIC_HIGHLIGHT_ENDPOINT=http://localhost:11434
SEMANTIC_HIGHLIGHT_MODEL=llama3.2:1b
SEMANTIC_HIGHLIGHT_TIMEOUT_SECONDS=180
```

## RabbitMQ management UI

Exposed for local development at **http://localhost:15672** (or whatever
`RABBITMQ_MANAGEMENT_PORT` you set), using the `RABBITMQ_USER` /
`RABBITMQ_PASSWORD` from your `.env`.

## Troubleshooting

- **`docker compose up` fails immediately / "Cannot connect to the Docker
  daemon"** — make sure Docker Desktop (or your Docker engine) is running.
- **`api` never becomes healthy** — check `docker compose logs api`. The
  backend fails fast and loudly if it can't reach Postgres or RabbitMQ, or
  if required environment variables are missing — the log will say exactly
  what's wrong. Confirm `.env` exists (`cp .env.example .env`) and that
  `postgres`/`rabbitmq` are already healthy (`docker compose ps`).
- **Flyway migration errors** — these show up in `docker compose logs api`
  at startup. Since this is local/dev data, `docker compose down -v` and
  starting again is usually the fastest fix.
- **Port already in use** — change `NGINX_PORT` (and/or
  `RABBITMQ_MANAGEMENT_PORT`) in `.env` to a free port.
- **Frontend shows "Backend: Offline"** — the browser reached Nginx but
  the `/api/*` proxy to the backend failed; check `docker compose ps` and
  `docker compose logs api nginx`.
- **Stale frontend after code changes** — Docker cached an old build;
  run `docker compose up --build web` (or `--build` on everything) to
  rebuild the image.

## Repository layout

```
apps/
├── web-angular/   Angular frontend
└── api-spring/    Spring Boot backend (modular monolith)
workers/           Standalone worker agent implementation
infra/
├── nginx/         Reverse proxy configuration
└── docker/        Shared Docker resources
docs/              Architecture, development, and roadmap docs
```
