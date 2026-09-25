# Development

## Prerequisites

- Docker + Docker Compose (v2) — for the full stack.
- Java 21 — for working on `apps/api-spring` outside Docker. Set
  `JAVA_HOME` to the JDK directory when running the Maven Wrapper on Windows.
- Node.js 22+ and npm — for working on `apps/web-angular` outside Docker.
- FFprobe — optional for import-only development, required for workers to claim
  `INSPECT_MEDIA` jobs. Install it with FFmpeg and set `FFPROBE_PATH` when the
  executable is not on `PATH`.
- FFmpeg — optional for import/inspection-only development, required for
  workers to claim `CREATE_CLIP` and `CREATE_SOCIAL_VERTICAL` jobs. Set
  `FFMPEG_PATH` when the executable is not on `PATH`.
- A local Whisper-compatible CLI — optional, required only for workers to claim
  `TRANSCRIBE_MEDIA`. Set `TRANSCRIPTION_RUNTIME`, `TRANSCRIPTION_COMMAND`,
  `TRANSCRIPTION_MODEL`, and optionally `TRANSCRIPTION_TIMEOUT_SECONDS`. Model
  installation is explicit; the worker does not download model binaries during
  startup. `WHISPER_CLI` targets the Python `whisper` CLI; `WHISPER_CPP`
  targets `whisper-cli` with a local `ggml-*.bin` model path.

## Running everything

See the root [README.md](../README.md) for the `docker compose up` workflow.
This is the recommended way to run the full stack — it's what CI/reviewers
will exercise and what the acceptance criteria are based on.

For a fresh development database, `.env` can provide bootstrap values for the
initial owner, workspace, and optional second user. The bootstrap runs only
with `SPRING_PROFILES_ACTIVE=dev`, hashes passwords with BCrypt, and is
idempotent. It can also create one development worker credential; only the
BCrypt secret hash is stored.

## Working on the backend alone

```bash
cd apps/api-spring
```

The app reads its configuration entirely from environment variables (see
`src/main/resources/application.yml`) and will fail to start if
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`, the `RABBITMQ_*`
equivalents, or the object-storage settings aren't set — there is no silent
fallback (e.g. no H2 in-memory database). The simplest way to get real
Postgres/RabbitMQ/MinIO
without running the whole stack:

```bash
docker compose up -d postgres rabbitmq minio
```

Then export the same variables docker-compose would have injected (see
`.env.example`) with `DB_HOST=localhost`, `RABBITMQ_HOST=localhost`, and
`STORAGE_ENDPOINT=http://localhost:9000`, then run:

```bash
./mvnw spring-boot:run
```

On Windows shells, use `mvnw.cmd` instead of `./mvnw`:

```powershell
mvnw.cmd spring-boot:run
```

Run the backend tests with:

```bash
./mvnw test
```

or on Windows:

```powershell
mvnw.cmd test
```

The current backend tests are lightweight unit and web-slice tests; they don't
need Postgres or RabbitMQ running.

## Working on the frontend alone

```bash
cd apps/web-angular
npm install
npm start          # ng serve
npm test           # unit tests
npm run build      # production build
```

`ng serve` on its own doesn't have anything to talk to at `/api`. Either:

- run the backend too (see above) and start the dev server with
  `npm start -- --proxy-config proxy.conf.json`, which proxies `/api/*` to
  `http://localhost:8080`, or
- run the whole stack via Docker Compose and open it through Nginx instead.

## Adding a new backend module

`accounts` and `publishing` are implemented as of Phase 10A; `publishing.instagram`
(the real Instagram Graph API integration) was added in Phase 10B;
`contentdrafts` (bridging a source asset/highlight candidate to a Publication
as one editable product object) was added in Phase 11A; `publishschedules`
(user-controlled future scheduling of a Draft's publication) was added in
Phase 11B — note this is deliberately a separate package from the Worker/Job
scheduling code in `jobs` (`SchedulingDecision` etc.); they solve different
problems and the naming is meant to keep them from being confused. `robots`
(persistent automation policies that orchestrate `contentdrafts` and
`publishschedules` unattended, within backend-enforced autonomy/provider
limits) was added in Phase 11C — a third, independent scheduling layer from
both `jobs` and `publishschedules`, with no `Robot -> Worker` relationship
anywhere. `contentsources` (controlled, workspace-scoped pools of existing
`MediaAsset`s a Robot may select from) was added in Phase 11D — it
deliberately has no dependency on `robots`, so it stays a reusable,
Robot-agnostic building block; `robots` depends on it, never the reverse.
`contentsuggestions` (AI-drafted hook/caption/hashtag suggestions for a
`ContentDraft`, always reviewed and applied by a human) was added in
Phase 12A — it depends on `contentdrafts` and `jobs`, and deliberately has
no dependency on `robots` and no reverse dependency from `robots` either;
Robots do not generate suggestions in this phase. `personas` (reusable,
workspace-scoped editorial identity a human may optionally attach to a
generation request) was added in Phase 12B — it has no dependency on
`contentdrafts`, `jobs`, or `robots` (a Persona is pure, reusable
configuration a caller reads or snapshots, never a Job/Robot participant),
and `contentsuggestions` depends on it for Persona resolution and
snapshotting. The one deliberate exception to the usual one-directional
package convention: `personas` imports `SuggestionLanguage`/
`SuggestionTone` from `contentsuggestions` to reuse them rather than fork a
parallel enum — see `personas/package-info.java` for the full rationale.
`analytics` now contains Phase 13A publication collection/attribution, the
Phase 13B read-only dashboard aggregation, and the Phase 13C deterministic
insight engine over that same data — no new package, since insights are
purely query/service/UI logic over already-immutable analytics;
`revenue` remains a placeholder. `experiments` (controlled A/B assignment —
`Experiment`/`ExperimentVariant`/`ExperimentAssignment`) was added in Phase
14A — it depends on `personas` (for `PersonaSnapshot`) and `analytics` (for
the shared `DashboardQuery`/`PublicationDashboardStore` outcome machinery),
but deliberately has **no** dependency on `robots`: assignment methods take
plain `Workspace`/`UUID`/`boolean` parameters rather than `Robot`/`RobotRun`
entities, so `robots` depends on `experiments` and not the reverse — the
same one-directional discipline `contentsources`/`robots` already
established. The package layout under `com.fdmultimedia.api` (`auth`,
`users`, `workspaces`, `accounts`, `robots`, `contentsources`, `assets`,
`jobs`, `workers`, `publishing`, `contentdrafts`, `publishschedules`,
`contentsuggestions`, `personas`, `analytics`, `experiments`, `revenue`,
`shared`) is where new domain logic should land.
See [ARCHITECTURE.md](ARCHITECTURE.md)
for what each package is for.

## Testing Instagram publishing locally

TEST publishing needs no configuration at all — this is the default. To
exercise the Instagram code paths locally without real Meta credentials:

- **Unit/contract tests** (`InstagramGraphClientTest` and friends) already
  point the client at a local fake HTTP server instead of Meta — run them
  with the rest of the backend test suite, no setup required.
- **A real end-to-end Instagram publish** additionally requires: a Meta App
  Dashboard app with the Instagram Login product added and
  `instagram_business_basic` + `instagram_business_content_publish`
  permissions; a real Instagram Business/Creator account you control; a
  publicly reachable HTTPS origin for `META_OAUTH_REDIRECT_URI` and
  `META_PUBLIC_BASE_URL` (Meta's servers cannot reach `localhost`, and this
  app will not weaken MinIO's privacy to work around that); and
  `SOCIAL_CREDENTIAL_ENCRYPTION_KEY` set to a real generated key
  (`openssl rand -base64 32`). Set `INSTAGRAM_ENABLED=true` and the other
  `META_*` variables from `.env.example`, restart the stack, and the
  Settings page will offer "Connect Instagram".
- Without those prerequisites, leave `INSTAGRAM_ENABLED=false` (the
  default) — the app starts normally, the Settings page shows "Instagram is
  not configured on this server yet", and TEST publishing is unaffected.
- **TikTok** follows the same pattern via `TikTokApiClientTest` and friends
  (local fake HTTP server, no live credentials needed for the automated
  suite). A real end-to-end TikTok publish additionally requires: a TikTok
  for Developers app with the Content Posting API product and `video.publish`
  scope approved; a real creator account that has authorized that app; a
  publicly reachable HTTPS `TIKTOK_REDIRECT_URI`; and the same
  `SOCIAL_CREDENTIAL_ENCRYPTION_KEY` used for Instagram. Set
  `TIKTOK_ENABLED=true` plus `TIKTOK_CLIENT_KEY`/`TIKTOK_CLIENT_SECRET` from
  `.env.example`, restart the stack, and the Settings page will offer
  "Connect TikTok". Until your app passes TikTok's audit, every post it makes
  is restricted to private (`SELF_ONLY`) visibility — this is a TikTok
  platform restriction, not something this app can or should bypass. Without
  those prerequisites, leave `TIKTOK_ENABLED=false` (the default).

## Testing scheduled publishing locally

`PUBLISH_SCHEDULER_ENABLED=true` (the default) is all TEST scheduling needs —
create a `ContentDraft`, `POST /api/content-drafts/{id}/schedules` a few
seconds beyond `PUBLISH_SCHEDULE_MIN_LEAD_SECONDS` (default 30) out to a TEST
account, and watch it: `GET /api/publish-schedules/{id}` shows no
`publicationId` until due, then `DISPATCHED` with one linked Publication
within one `PUBLISH_SCHEDULER_POLL_MS` cycle (default 15s) after the due
time, whether or not a Worker is currently running (an offline Worker just
leaves the resulting `PUBLISH_MEDIA` Job `QUEUED`). Set
`PUBLISH_SCHEDULER_ENABLED=false` to disable the dispatcher entirely (e.g.
for a deployment that only wants immediate publishing) — schedules can still
be created and cancelled, they simply never dispatch.

## Testing robot automation locally

`ROBOT_AUTOMATION_ENABLED=true` (the default) and no other configuration is
needed for TEST-provider automation — create a Robot
(`POST /api/robots`) with `sourcePolicy: "EXISTING_ASSET"`, an existing
READY+INSPECTED `sourceAssetId`, and `autonomyMode: "AUTO_SCHEDULE"` against
a TEST `SocialAccount`, then either
`POST /api/robots/{id}/run` for an immediate run or set `cadenceType:
"INTERVAL"` and wait for `ROBOT_SCHEDULER_POLL_MS` (default 15s) to claim it.
`GET /api/robot-runs/{id}` shows the run walking through its provenance
columns (`highlightAnalysisId` → `highlightCandidateId` → `contentDraftId` →
`publishScheduleId`) to `SUCCEEDED`, with a normal `PUBLISH_MEDIA` Job queued
for a Worker exactly as if a human had scheduled the same Draft by hand. Try
the same Robot with `autonomyMode: "REVIEW_REQUIRED"` to see a
`RobotApproval` appear at `GET /api/robot-approvals?status=PENDING` instead
of an immediate schedule, and `POST /api/robot-approvals/{id}/approve` (or
`/reject`) to resolve it.

To exercise the real-provider safety boundary without an Instagram account,
attempt `POST /api/robots` with `autonomyMode: "AUTO_SCHEDULE"` against any
non-`TEST` account (or `PATCH` an existing Robot to point at one) — the API
rejects it with 409 `AUTONOMOUS_PROVIDER_NOT_ALLOWED` regardless of Meta
configuration; `DRAFT_ONLY`/`REVIEW_REQUIRED` allow any account, including
Instagram, because a human still decides before anything publishes.

## Testing dynamic content sources locally

Create a `ContentSource` (`POST /api/content-sources`), add a few existing
`ORIGINAL` assets to it in the order you want to observe
(`POST /api/content-sources/{id}/assets`, body `{"mediaAssetId": "..."}`) —
only assets already imported through the normal Content page/import API can
join; there is no URL field anywhere on a Robot or a ContentSource. Create a
Robot with `sourcePolicy: "CONTENT_SOURCE"`, `contentSourceId`, and
`selectionPolicy: "OLDEST_UNPROCESSED"` (or `"NEWEST_UNPROCESSED"`) instead
of `sourceAssetId`, then `POST /api/robots/{id}/run` repeatedly: each run's
`GET /api/robot-runs/{id}` shows a different `sourceAssetId` as the source
is worked through in order, and once every eligible asset has been used by
that Robot, the next run terminates immediately with `failureCode:
"NO_ELIGIBLE_SOURCE"` — no Draft, Job, or Schedule is created. A second
Robot pointed at the same source may freely select an asset the first Robot
already consumed (consumption is per-Robot, not global). `POST
/api/content-sources/{id}/pause` makes any further run for a Robot
configured against it fail fast with 409 `CONTENT_SOURCE_UNAVAILABLE`
instead — `/resume` restores it.

Set `ROBOT_AUTOMATION_ENABLED=false` to stop the scheduler from claiming or
reconciling anything (a global kill switch, verifiable with a plain
`docker compose up -d api` restart) while every other API keeps working; a
per-robot `POST /api/robots/{id}/pause` stops just that Robot from starting
new runs without touching Jobs/Drafts/Schedules its past runs already
created.

## Testing AI content enrichment locally

`CONTENT_AI_ENABLED=true` with `CONTENT_AI_PROVIDER=DETERMINISTIC_TEST` (both
defaults) need no configuration at all: generate a suggestion for any READY
`ContentDraft` with `POST /api/content-drafts/{draftId}/suggestions`,
`{"language": "ENGLISH", "tone": "CASUAL"}`, and a `GENERATE_SOCIAL_COPY`
Job produces a deterministic hook/caption/hashtags within one Worker poll
cycle — no external credentials needed, and this is what the automated test
suite uses end to end. `GET /api/content-drafts/{draftId}/suggestions` lists
history newest-first; `POST /api/content-suggestions/{id}/apply` composes
the suggestion into `Draft.caption`, and `POST
/api/content-suggestions/{id}/discard` keeps it historical instead.

To exercise a real local LLM instead of the deterministic provider, run
[Ollama](https://ollama.com) with a pulled model (e.g. `ollama pull
llama3.2`), then set on the **Worker**: `CONTENT_AI_RUNTIME=OLLAMA`,
`CONTENT_AI_ENDPOINT=http://localhost:11434` (or wherever Ollama is
reachable from the Worker process), and `CONTENT_AI_MODEL` to match a model
`ollama list` actually shows — the Worker only registers the `OLLAMA`
provider if that exact model is reachable at startup, otherwise it silently
falls back to logging `DETERMINISTIC_TEST only`. Set on the **backend**:
`CONTENT_AI_PROVIDER=OLLAMA` and the same `CONTENT_AI_MODEL`, then restart
the API. Generation now takes real wall-clock time (tens of seconds on a
CPU-only local model) and produces genuine model output; `latencyMs` on the
resulting suggestion reflects this. The backend never needs the Ollama
endpoint or any provider secret — only the Worker's own environment does.

To exercise the disabled path, set `CONTENT_AI_ENABLED=false` and restart
the API: the app starts normally, and generation is rejected synchronously
with 409 `AI_DISABLED` (no Job is ever created). To exercise a provider
failure without touching real infrastructure, request generation while the
backend's configured provider (e.g. `OLLAMA`) is not registered on the
Worker (e.g. the Worker never set `CONTENT_AI_RUNTIME`) — the Job fails
immediately with the safe, terminal `AI_PROVIDER_UNAVAILABLE` code and the
suggestion moves straight to `FAILED` with no partial output.

## Testing Personas locally

Create a Persona (`POST /api/personas`), minimally `{"name": "Tech Romania",
"voiceDescription": "Direct, informed and energetic."}` — every other field
is optional. Generate a suggestion referencing it with `POST
/api/content-drafts/{draftId}/suggestions`,
`{"personaId": "<id>"}` and no `language`/`tone`: the resolved values in the
response come from the Persona's own `defaultLanguage`/`defaultTone`
(`AUTO`/`NEUTRAL` if the Persona didn't set them either). Pass an explicit
`language`/`tone` alongside `personaId` to confirm the request value always
wins over the Persona default. With `DETERMINISTIC_TEST`, the resulting
`hook`/`caption` visibly include the Persona's name (e.g. `"... (Tech
Romania voice)"`) — a quick way to confirm the Persona actually reached the
Worker without needing a real LLM.

To see the snapshot/staleness distinction concretely: generate suggestion A
with a Persona, `PATCH` that same Persona's `voiceDescription`, generate
suggestion B with the same `personaId` — A and B will have different
`promptVersion`-scoped fingerprints and different underlying snapshots
(visible directly in Postgres via `SELECT persona_voice_description FROM
content_suggestions WHERE id = ...`), but `POST
/api/content-suggestions/{A}/apply` still succeeds as long as the Draft
itself hasn't changed — a Persona edit alone never produces
`SUGGESTION_STALE`. Only a Draft title/caption edit
(`PATCH /api/content-drafts/{id}`) does that. `POST
/api/personas/{id}/archive` immediately blocks that `personaId` from new
generation (`409 PERSONA_ARCHIVED`) but never invalidates suggestions
already generated from it — `POST /api/personas/{id}/restore` reverses it.

## Testing Robot AI enrichment locally

Create a Robot with `aiPolicy: "GENERATE_FOR_REVIEW"` (and optionally
`personaId`) instead of the default `"NO_AI"`, e.g. `POST /api/robots`
`{"name": "AI Robot", "autonomyMode": "DRAFT_ONLY", "sourcePolicy":
"EXISTING_ASSET", "sourceAssetId": "<id>", "cadenceType": "MANUAL_ONLY",
"aiPolicy": "GENERATE_FOR_REVIEW", "personaId": "<personaId>"}`, then `POST
/api/robots/{id}/run`. Poll `GET /api/robot-runs/{runId}` — it progresses
through `WAITING_FOR_DRAFT` → `WAITING_FOR_AI` (the automatic
`ContentSuggestion` and its `GENERATE_SOCIAL_COPY` Job exist;
`contentSuggestionId` is populated) → `WAITING_FOR_AI_REVIEW` once the
suggestion is `READY`. This state is a deliberately separate gate from the
pre-existing publishing-approval `WAITING_FOR_REVIEW` state; `POST
/api/content-suggestions/{suggestionId}/apply` (the same endpoint a human
uses) resumes the Robot's existing autonomy automatically — no dedicated
"continue Robot" endpoint exists or is needed, since the background
`RobotAutomationScheduler` poller (or any subsequent read of the run) picks
the `APPLIED` suggestion up on its own. Use `"aiPolicy":
"GENERATE_AND_APPLY"` instead to skip the review gate entirely: the Robot
applies the suggestion itself through that same endpoint before continuing
its autonomy mode.

To see the stale-protection path, create a `GENERATE_AND_APPLY` Robot,
`run` it, and — before the automatic Apply happens — `PATCH
/api/content-drafts/{draftId}` with a different `caption` (poll `GET
/api/content-drafts/{draftId}/suggestions` rather than the RobotRun itself
while waiting, since a RobotRun read is itself a reconciliation trigger).
The RobotRun fails with `failureCode: "ROBOT_AI_SUGGESTION_STALE"` and the
Draft keeps your edited caption. To force an AI failure, restart the API
with `CONTENT_AI_ENABLED=false` (see the AI content enrichment section
above) — an AI-enabled Robot's next run fails with `failureCode:
"ROBOT_AI_DISABLED"` rather than hanging in `WAITING_FOR_AI`; a `NO_AI`
Robot is completely unaffected by this switch. `POST
/api/content-suggestions/{suggestionId}/discard` on a
`WAITING_FOR_AI_REVIEW` suggestion fails the run with
`ROBOT_AI_SUGGESTION_DISCARDED` and never auto-regenerates — start a new
`run` instead.

## Database migrations

Migrations live in `apps/api-spring/src/main/resources/db/migration` and
run automatically on startup via Flyway. Add a new
`V<next-number>__description.sql` file — never edit a migration that has
already shipped.

## Authentication and CSRF

The browser uses secure server-side session authentication. Angular sends
same-origin API requests to `/api/*`; Nginx forwards them to Spring Boot.
Spring Security issues an `HttpOnly` session cookie and a readable
`XSRF-TOKEN` cookie. Angular mirrors that CSRF token in the `X-XSRF-TOKEN`
header for protected mutating requests such as logout.

Worker agent endpoints under `/api/worker-agent/**` use `Authorization:
WorkerToken <credential-id>.<secret>` instead of browser sessions. CSRF is
ignored only for those machine endpoints and remains enabled for session APIs.

Job creation/list/detail endpoints under `/api/jobs` are normal browser
session APIs. Keep CSRF enabled for mutating human requests and always derive
workspace scope from the authenticated membership.

Asset endpoints under `/api/assets` are also session APIs. `POST
/api/assets/import` accepts only a direct HTTP/HTTPS URL and derives workspace
and created-by user from the session. `GET /api/assets/{id}/download-url`
returns a short-lived presigned GET URL only for READY assets in the current
workspace.

Worker import endpoints under `/api/worker-agent/assets/imports/**` are machine
APIs. Workers request a presigned PUT URL, upload directly to private object
storage, and report completion/failure. Permanent MinIO/S3 credentials remain
server-side.

`/api/social-accounts/**` and `/api/publications/**` are normal session APIs
(CSRF-protected mutations, workspace-derived from the session), including the
Instagram OAuth `POST /connect` start endpoint and the
`POST /{accountId}/disconnect` endpoint. The one deliberate exception is
`GET /api/public-media/{token}` — permitAll and outside CSRF, because a real
external provider (Meta) must be able to fetch media over the public
internet and cannot present a session cookie. See
[ARCHITECTURE.md](ARCHITECTURE.md#instagram-publishing-phase-10b) for why
that endpoint is still safe: the token is unguessable, publication-scoped,
and stops working once the Publication leaves `PUBLISHING`.

## Working on the worker agent

```bash
cd workers/java-agent
../../apps/api-spring/mvnw -f pom.xml test
../../apps/api-spring/mvnw -f pom.xml package
```

On Windows PowerShell:

```powershell
..\..\apps\api-spring\mvnw.cmd -f pom.xml test
..\..\apps\api-spring\mvnw.cmd -f pom.xml package
```

Run it against the Docker stack with the development worker credential from
`.env`:

```powershell
$env:FDM_API_BASE_URL = "http://localhost:8080/api"
$env:FDM_WORKER_TOKEN = "$env:BOOTSTRAP_WORKER_CREDENTIAL_ID.$env:BOOTSTRAP_WORKER_CREDENTIAL_SECRET"
java -jar target\worker-agent-0.1.0-SNAPSHOT.jar
```

Useful worker environment variables:

- `FDM_WORKER_NAME` — friendly name reported to the Compute and Jobs pages.
- `FDM_WORKER_ID_FILE` — local file that stores the generated installation ID.
- `FDM_WORKER_HEARTBEAT_SECONDS` — heartbeat interval, default 10 seconds.
- `FDM_WORKER_JOB_POLL_SECONDS` — job polling interval, default 3 seconds.
- `WORKER_MAX_ACTIVE_JOBS` — reported execution capacity, default 1. The Java
  worker avoids claim polling while its local active job count is at capacity.
- `FFPROBE_PATH` — FFprobe executable path, default `ffprobe`.
- `FFMPEG_PATH` — FFmpeg executable path, default `ffmpeg`.
- `TRANSCRIPTION_RUNTIME` — `WHISPER_CLI` or `WHISPER_CPP`, default
  `WHISPER_CLI`.
- `TRANSCRIPTION_COMMAND` — local Whisper-compatible CLI, default `whisper`.
- `TRANSCRIPTION_MODEL` — local model name/path passed to that CLI, default
  `base`.
- `TRANSCRIPTION_TIMEOUT_SECONDS` — inference timeout, default 900 seconds.

The Phase 9B worker registers, heartbeats with lightweight telemetry, polls for
jobs, and remains compatible with the Phase 9C observability layer.

Phase 9C operational APIs are available to authenticated workspace users:

* `GET /api/scheduling/overview?window=24h`
* `GET /api/scheduling/workers?window=24h`
* `GET /api/scheduling/decisions?window=24h&limit=25`

Supported windows are `1h`, `24h`, `7d`, and `30d`; decision limits are clamped
to 1–100. Configure retention with `SCHEDULING_DECISION_RETENTION_DAYS` and the
low-frequency cleanup schedule with `SCHEDULING_DECISION_CLEANUP_CRON`.

The Phase 9B worker registers, heartbeats with lightweight telemetry, polls for
a job, starts it, executes
`SYSTEM_TEST`, `IMPORT_MEDIA`, `INSPECT_MEDIA`, `CREATE_CLIP`, or
`CREATE_SOCIAL_VERTICAL`, `ANALYZE_HIGHLIGHTS`, or `TRANSCRIBE_MEDIA`, renews active
media-job leases, and reports success or failure. `SYSTEM_TEST` is limited to a
bounded message and sleep duration; `IMPORT_MEDIA` is limited to direct
HTTP/HTTPS media-file ingestion; `INSPECT_MEDIA` is limited to read-only
FFprobe metadata inspection of already stored originals; `CREATE_CLIP` is
limited to controlled FFmpeg MP4 clip creation from a stored source asset; and
`CREATE_SOCIAL_VERTICAL` is limited to the fixed 1080x1920 center-crop preset.
`ANALYZE_HIGHLIGHTS` uses a deterministic local analyzer and does not call any
AI provider. `TRANSCRIBE_MEDIA` uses only a configured local transcription CLI
with controlled invocation and structured JSON output. The worker does not
execute arbitrary commands.

For media imports, the worker validates the URL, follows only bounded
revalidated redirects, streams to a temporary file with size and timeout
limits, computes SHA-256, rejects obvious non-media responses, uploads through
a presigned PUT URL, reports metadata, and deletes the temporary file.

For media inspection, the worker advertises `INSPECT_MEDIA` only if
`ffprobe -version` succeeds. It obtains a short-lived presigned GET URL from
the API, downloads the stored original to a temporary file, runs FFprobe with a
fixed argument list, reports duration/resolution/codec/container metadata, and
deletes the temporary file. If FFprobe is missing, inspection jobs remain
queued for a compatible worker; imports and `SYSTEM_TEST` jobs still work.

Manual distributed execution check:

1. Start the Docker stack and log in through `http://localhost:8080`.
2. Leave the worker stopped and create a `SYSTEM_TEST` job from Jobs; it should
   stay `QUEUED`.
3. Start the worker agent; the job should move through `ASSIGNED`, `RUNNING`,
   and `SUCCEEDED`.
4. Create several jobs; each should complete once with the same workspace.
5. Stop the worker during a longer job and restart it after the lease expires;
   the job should retry until `maxAttempts`, then either succeed or fail.

Manual media import check:

1. Start `docker compose up --build -d` and log in through
   `http://localhost:8080`.
2. Leave the worker stopped, open Content, and submit a small direct
   HTTP/HTTPS media file URL you are authorized to use; the asset should remain
   `PENDING` and the job `QUEUED`.
3. Start the worker; it should register, claim `IMPORT_MEDIA`, move the asset
   through `IMPORTING -> READY`, and mark the job `SUCCEEDED`.
4. Check MinIO at `http://localhost:9001` and confirm the object exists under
   `workspaces/{workspaceId}/assets/{assetId}/original` in the private
   `media-assets` bucket.
5. Submit a controlled non-media URL and verify a safe `FAILED` asset with no
   leaked credentials.

Manual media inspection check:

1. Install FFprobe and confirm `ffprobe -version` works, or set
   `$env:FFPROBE_PATH` to the executable before starting the worker.
2. Import a small valid media file through Content.
3. Confirm the import job reaches `SUCCEEDED`, the asset reaches `READY`, and
   a separate `INSPECT_MEDIA` job is created.
4. Confirm the worker claims the inspection job only when FFprobe is available.
5. Confirm Content shows inspection metadata such as duration, resolution,
   codecs, frame rate, bitrate, and an `Inspected` status.
6. Stop or hide FFprobe and restart the worker; it should log that
   `INSPECT_MEDIA` is disabled and leave inspection jobs queued.

Manual clip derivative check:

1. Install FFmpeg and confirm `ffmpeg -version` works, or set
   `$env:FFMPEG_PATH` to the executable before starting the worker.
2. Import and inspect a small media file through Content.
3. On a READY + INSPECTED source, create a clip with `startMs=3000` and
   `durationMs=5000`.
4. Confirm the output asset is a `CLIP` derivative of the source and the source
   asset storage key/checksum remain unchanged.
5. Confirm the `CREATE_CLIP` job reaches `SUCCEEDED`, the derived object exists
   under its own asset ID in MinIO, and an `INSPECT_MEDIA` job is created for
   the derived asset.
6. Confirm the derived asset becomes `INSPECTED` and its duration is close to
   the requested interval.

Manual social vertical preset check:

1. Install FFmpeg and FFprobe, or set `$env:FFMPEG_PATH` / `$env:FFPROBE_PATH`
   before starting the worker.
2. Import and inspect a small landscape media file, for example 640x360 with
   H.264 video and AAC audio.
3. On the READY + INSPECTED source, click **Make 9:16** in Content.
4. Confirm the output asset is a `SOCIAL_VERTICAL` derivative of the selected
   source and the source checksum/size remain unchanged.
5. Confirm `CREATE_SOCIAL_VERTICAL` reaches `SUCCEEDED`, the derived object is
   stored under its own asset ID in the private bucket, and automatic
   `INSPECT_MEDIA` runs.
6. Download through `GET /api/assets/{id}/download-url` and inspect with
   FFprobe; the output should be MP4/H.264, 1080x1920, AAC when source audio
   exists, and approximately the same duration as the source.
7. Direct bucket access should remain forbidden. The preset is deterministic
   center crop only; subject-aware reframing is intentionally not implemented.

Manual highlight candidate check:

1. Start the Docker stack and a worker. FFmpeg is not required for analysis,
   but it is required for the later candidate-to-clip step.
2. Import and inspect a small video asset so it is `READY` + `INSPECTED`,
   has `hasVideo=true`, and has a known duration.
3. In Content, click **Find Highlights**. The API should create one
   `HighlightAnalysis` and one `ANALYZE_HIGHLIGHTS` job.
4. Confirm the analysis moves `PENDING -> RUNNING -> SUCCEEDED` and shows
   candidates labelled `DETERMINISTIC_V1`.
5. Click **Create Clip** on one candidate. This should create a normal
   `CREATE_CLIP` job and derived clip asset, then automatic `INSPECT_MEDIA`.
6. Confirm no media object is created merely by the analysis itself.

Manual transcription check:

1. Install FFmpeg and a local Whisper-compatible CLI/model. Confirm the CLI
   works independently and set `$env:TRANSCRIPTION_RUNTIME`,
   `$env:TRANSCRIPTION_COMMAND`, and `$env:TRANSCRIPTION_MODEL` before
   starting the worker. For whisper.cpp, use `TRANSCRIPTION_RUNTIME=WHISPER_CPP`
   and point `TRANSCRIPTION_MODEL` to the local `ggml-*.bin` file.
2. Import and inspect a short owned media fixture with audible speech.
3. In Content, click **Transcribe** on the READY + INSPECTED asset.
4. Confirm the `TRANSCRIBE_MEDIA` job moves `QUEUED -> ASSIGNED -> RUNNING ->
   SUCCEEDED` and the transcript becomes `SUCCEEDED`.
5. Confirm timestamped segments are visible in Content and recognizably match
   the spoken audio. Perfect accuracy is not required for development smoke
   testing.

Manual semantic highlight check:

1. Start a local Ollama runtime and pull a small local model, for example:

   ```text
   docker run -d --name fdm-ollama -p 11434:11434 -v fdm_ollama:/root/.ollama ollama/ollama:latest
   docker exec fdm-ollama ollama pull llama3.2:1b
   ```

2. Start the Java worker with semantic highlight settings:

   ```text
   SEMANTIC_HIGHLIGHT_RUNTIME=OLLAMA
   SEMANTIC_HIGHLIGHT_ENDPOINT=http://localhost:11434
   SEMANTIC_HIGHLIGHT_MODEL=llama3.2:1b
   ```

3. Confirm worker startup logs that the semantic provider is available and the
   worker advertises `TRANSCRIPT_SEMANTIC_V1` in addition to
   `DETERMINISTIC_V1`.
4. Use a READY + INSPECTED video asset with a `SUCCEEDED` transcript.
5. In Content, click **Semantic Highlights**. The API should create an
   `ANALYZE_HIGHLIGHTS` job with analyzer `TRANSCRIPT_SEMANTIC_V1`.
6. Confirm the job reaches `SUCCEEDED`, candidates are persisted, and candidate
   reasons are grounded in transcript text. The model may be locally small, so
   quality only needs to be recognizably transcript-related for development
   acceptance.

The semantic provider receives transcript windows only. Do not send media URLs,
storage keys, credentials, or arbitrary prompt material from browser input to a
model provider.

Manual Semantic Highlights V2 check:

Manual Transcript-aware V3 check:

1. Import and inspect English speech media, then run real transcription.
2. Confirm the Worker heartbeat advertises `DETERMINISTIC_V3`.
3. In Content choose **Transcript-aware V3** and inspect transcript provenance, excerpt, component scores and rank.
4. Create a clip and 9:16 derivative from rank 1; both must become `READY + INSPECTED` and remain private.
5. `TOP_HIGHLIGHT` prefers V3. No Ollama or cloud AI runtime is required.

Manual Semantic Highlights V2 check (legacy compatibility):

1. No extra setup needed — `DETERMINISTIC_V2` is deterministic Java on the
   Worker (no Ollama, no network calls) and is always registered, unlike
   `TRANSCRIPT_SEMANTIC_V1` above.
2. Use a READY + INSPECTED video asset with a `SUCCEEDED` transcript (same
   prerequisite as Transcript AI).
3. In Content, click **Semantic Highlights V2**. The API should create an
   `ANALYZE_HIGHLIGHTS` job with analyzer `DETERMINISTIC_V2`.
4. Confirm the job reaches `SUCCEEDED` and candidates show a score breakdown
   under "Why this highlight" (opening, completeness, speech/information
   density, boundary fit, transcript coverage) plus deterministic explanation
   labels and a transcript excerpt.
5. Re-run the exact same analysis request (same asset, no config change) and
   confirm it reuses the existing analysis (same `configFingerprint`, no
   second `ANALYZE_HIGHLIGHTS` job) rather than duplicating work.
6. Confirm re-running the analyzer twice on the same transcript produces
   byte-identical candidate timestamps, scores, and ranking — this analyzer
   has no randomness anywhere in its pipeline.

Manual telemetry check:

1. Start the Docker stack and a Java worker.
2. Open Compute. The worker should show static metadata, agent version,
   capabilities, telemetry freshness, active jobs, CPU load when available,
   and available memory when available.
3. Create a longer `SYSTEM_TEST` job. During execution, Compute should show
   `1 / 1 active` for the worker, then return to `0 / 1 active` after
   completion.
4. Stop the worker and wait past the offline threshold. The worker should
   become offline; stale telemetry should not be presented as current.

Phase 9B records per-attempt execution metrics from server timestamps for
success, failure, and lease-expiry recovery, then uses successful `executionMs`
history after enough samples exist. Claiming still happens through PostgreSQL
row locking: the API locks a bounded FIFO window of compatible jobs with
`FOR UPDATE SKIP LOCKED`, scores those candidates for the polling worker, and
claims one inside the same transaction. Missing telemetry or insufficient
history falls back to FIFO; failure-rate scoring, predictive placement, and
autoscaling remain deferred.

## Publication analytics (Phase 13A)

Publish to a TEST SocialAccount, then open Analytics from the published item.
The API schedules collection without a browser: 15, 60, 360, 1440, 4320,
and 10080 minutes after publication. For local acceptance, override
`PUBLICATION_ANALYTICS_CADENCE_MINUTES` with six increasing positive minute
values or use the bounded manual Refresh action. Other settings are
`PUBLICATION_ANALYTICS_ENABLED`, `PUBLICATION_ANALYTICS_POLL_MS`,
`PUBLICATION_ANALYTICS_BATCH_SIZE`, `PUBLICATION_ANALYTICS_CLAIM_LEASE`, and
`PUBLICATION_ANALYTICS_MANUAL_REFRESH_MINIMUM`. Defaults are enabled, 60 s,
20, 2 min, and 5 min. Do not set a tight cadence in production.

`GET /api/publications/{id}/analytics` returns at most 100 immutable snapshots;
`/latest`, `/state`, and `/attribution` expose current observation and frozen
provenance. `POST /api/publications/{id}/analytics/refresh` obeys the same
CSRF/session rules as other human writes and is rate-limited. Workspace list
`GET /api/analytics/publications?from=...&to=...&limit=...` requires a bounded
one-year interval and at most 100 results. A missing metric is null (rendered
as a dash), distinct from an observed zero. TEST is a deterministic simulation.
Instagram insights are not silently attempted with publishing-only scopes;
the existing account requires separately verified analytics permission before
real collection can be enabled. Disabling analytics never disables publishing.

## Publication analytics dashboard (Phase 13B)

The Analytics page's "Published content" band (`GET /api/analytics/dashboard/summary`,
`/trend`, `/breakdown`, `/filters`) reads the same Phase 13A snapshots/attribution
rows with no new write path. All four share `dateFrom`/`dateTo` (ISO dates,
defaulting to the last 30 UTC days, capped at 365 days and never past today),
`window` (`LATEST`/`H24`/`H72`/`D7`), and optional `provider`/`robotId`/
`personaId`/`contentSourceId`/`origin` (`MANUAL`/`ROBOT`)/`aiUsage`
(`AI_APPLIED`/`NO_APPLIED_AI`) filters — an unrecognized enum value or
malformed date/UUID is a plain `400`, never silently ignored. `trend` additionally
takes `metric` (one of the seven normalized metrics) and `breakdown` takes
`dimension` (`ROBOT`/`PERSONA`/`CONTENT_SOURCE`/`PROVIDER`/`ORIGIN`/`AI_USAGE`).
The Angular page keeps all of these as query params, so a filtered/grouped view
is a shareable URL.

To see a non-trivial dashboard locally without waiting on the real cadence,
publish a few TEST items (manually and through a Robot, with and without a
Persona/ContentSource) and use the manual Refresh action (or an accelerated
`PUBLICATION_ANALYTICS_CADENCE_MINUTES` override, see above) to populate
snapshots — the `LATEST` window shows immediately, while `H24`/`H72`/`D7`
only populate once a snapshot actually falls in that age band (the coverage
line above the KPIs reports "too young" vs. "missing snapshot" separately, so
an empty aggregate is distinguishable from a real collection gap). Renaming
a Robot/Persona/ContentSource after publishing and reloading the breakdown
confirms grouping still uses the frozen snapshot name, not the live one.

## Deterministic performance insights (Phase 13C)

`GET /api/analytics/insights` (bounded, automatic) and `GET
/api/analytics/insights/compare` (explicit) read the same Phase 13A/13B data
with no new write path and no AI provider involved — `PERFORMANCE_INSIGHTS_V1`
is plain deterministic Java. Both accept the same filters as the dashboard
(`dateFrom`/`dateTo`/`window`/`provider`/`robotId`/`personaId`/
`contentSourceId`/`origin`/`aiUsage`); `/compare` additionally requires
`dimension` (`ROBOT`/`PERSONA`/`CONTENT_SOURCE`/`PROVIDER`/`ORIGIN`/
`AI_USAGE`), `leftSegmentId`/`rightSegmentId` (a UUID or `NONE` for the three
entity dimensions; `MANUAL`/`ROBOT`, `AI_APPLIED`/`NO_APPLIED_AI`, or
`TEST`/`INSTAGRAM` for the other three), `metric` (one of the seven
normalized metrics), and optional `statistic` (`MEDIAN`, the default, or
`AVERAGE`).

Three thresholds gate every comparison, `app.performance-insights.*`
(`PERFORMANCE_INSIGHTS_MIN_SAMPLE_SIZE` default `5`,
`PERFORMANCE_INSIGHTS_MIN_COVERAGE` default `0.60`,
`PERFORMANCE_INSIGHTS_MATERIAL_DIFFERENCE_PERCENT` default `10`) — below
either the sample or coverage threshold you get `INSUFFICIENT_SAMPLE`/
`LOW_COVERAGE`, not a directional claim; below the material-difference
threshold you get `SIMILAR_OBSERVED`, not a fabricated "no difference." For
local acceptance with a small dataset, temporarily lower
`PERFORMANCE_INSIGHTS_MIN_SAMPLE_SIZE` (e.g. to `2`) rather than trusting a
demo built on the production default — and restore the default afterward,
since a low threshold is not a defensible production setting.

To see a genuine `TOO_YOUNG` result, compare any dimension under `window=H72`
or `window=D7` shortly after publishing — the automatic `/insights` response's
`notices` array reports the same maturity gap in plain language
("N recent publication(s) have not yet reached the 72-hour observation
window"). To see `METRIC_UNAVAILABLE`, compare a metric a provider never
populates (only realistic for a real Instagram field once connected — TEST
always populates all seven). Comparing a segment to itself, an unsupported
`dimension`/`metric`/`statistic`, or a malformed UUID segment is a plain
`400`. A Robot/Persona/ContentSource renamed between two publications still
reports as one segment (its most recently published name), not two — see
`docs/ARCHITECTURE.md` for the query-level fix this phase made to
`PublicationDashboardStore` to guarantee that.

## Controlled experiments & A/B testing foundation (Phase 14A)

`POST /api/experiments` creates a DRAFT PERSONA experiment in one call — name,
hypothesis, `targetObservationWindow` (`H24`/`H72`/`D7`; `LATEST` is rejected),
`primaryMetric` (one of the seven normalized metrics), and a distinct,
ACTIVE Persona for each of `variantAPersonaId`/`variantBPersonaId`. While
DRAFT, `PATCH /api/experiments/{id}` can still edit any of those fields
(never the factor). `POST /api/experiments/{id}/activate` freezes both
variants' Persona configuration (re-validates distinctness and ACTIVE status
at that moment) and transitions to ACTIVE; `pause`/`resume`/`complete`/
`cancel` follow from there, and a terminal experiment (`COMPLETED`/
`CANCELLED`) accepts no further transition.

To attach a Robot, set `experimentId` on `POST`/`PATCH /api/robots` — the
Robot's own `aiPolicy` must be `GENERATE_FOR_REVIEW` or `GENERATE_AND_APPLY`
(never `NO_AI`), and the Experiment must not be terminal. Once attached,
every new `RobotRun` (manual "Run Now" or the scheduler) is assigned a
variant **at run creation**, before any AI generation begins — the response
already carries `experimentVariantKey`. Assignment fails with
`ROBOT_EXPERIMENT_NOT_ACTIVE` (a manual run: a `409`; a scheduled run: logged
and skipped, exactly like `DAILY_LIMIT_REACHED`) whenever the Experiment is
DRAFT/PAUSED/terminal — pausing never contaminates the population, it only
blocks *new* assignments; an already-assigned run keeps going and may still
finish, publish, and reach `PublicationAttribution`.

To prove the frozen-treatment guarantee locally: activate an experiment,
`PATCH` one of its variant's live Personas to a different language/tone/voice,
then run an experimental Robot until it lands on that variant (repeat "Run
Now" against fresh EXISTING_ASSET Robots if needed — assignment is balanced,
not random, so it converges quickly) — the resulting `ContentSuggestion`'s
`language`/`tone`/caption still reflect the **pre-edit** frozen snapshot, not
the live edit. Archiving that Persona afterward doesn't affect it either.

`GET /api/experiments/{id}/assignments` (bounded, most-recent-first) and
`GET /api/experiments/{id}/outcomes` are both read-only. Outcomes reuse Phase
13B's exact snapshot-selection semantics at the Experiment's own fixed
`targetObservationWindow`/`primaryMetric` — per-variant `assignedRuns`/
`failedRuns`/`runsWithDraft`/`publishedCount`/`eligibleByAgeCount`/
`analyticsPublicationCount`/`metricSampleCount`/`coverage`/`average`/`median`,
plus plain-language maturity notices — and never a `winner`/`score`/
`confidence` field. There is no accelerated-time trick for maturity in this
phase; to see non-zero `eligibleByAgeCount`, either wait past the
Experiment's own window or (for local iteration) create the experiment
against already-old TEST publications reused from an earlier phase's
dataset. Concurrent assignment safety can be exercised locally by firing
several `POST /api/robots/{id}/run` requests in parallel against distinct
Robots on the same Experiment and confirming `GET
.../assignments` shows one row per run, no duplicates, and a
balanced A/B split (difference at most one).

## Statistical experiment analysis (Phase 14B)

`GET /api/experiments/{id}/analysis` takes no query parameters — it always
uses the Experiment's own frozen `targetObservationWindow`/`primaryMetric`,
never a caller-supplied override. It works for every Experiment status
(`DRAFT` simply has no assignments yet, so both populations report
`NO_OBSERVATIONS`; `CANCELLED` remains readable if assignment data exists);
only `ACTIVE` adds `activeExperimentWarning` to the response.

Threshold: `app.experiment-analysis.min-sample-per-variant`
(`EXPERIMENT_ANALYSIS_MIN_SAMPLE_PER_VARIANT`, default `5` — deliberately
the same considered-default rationale `PerformanceInsightProperties` already
documents, not a shortcut). Below it, a population reports
`INSUFFICIENT_SAMPLE` with descriptive statistics only (mean/median/
standard deviation/min/max) and every inferential field (`standardError`/
`degreesOfFreedom`/`confidenceIntervalLower`/`confidenceIntervalUpper`/
`pValue`/`standardizedEffectSize`) forced to `null` — never a value computed
from too little data and presented as if valid.

To see a genuine `READY` result locally: an Experiment needs at least
`min-sample-per-variant` **published, matured-to-the-target-window,
snapshot-bearing** observations in both arms. The `H24` window is the
fastest to reach without any DB manipulation; for `H72`/`D7` locally,
directly updating a real TEST Publication's `published_at` (and, if
already collected, its snapshot's `collected_at`/`publication_age_seconds`)
in Postgres to simulate elapsed time is an accepted, explicitly-called-out
shortcut for *runtime acceptance only* (never for automated tests, which
use `WelchStatisticsTest`'s direct math instead) — document exactly what
you changed and why, and never touch a non-TEST provider's data this way.

To see `INSUFFICIENT_VARIANCE`: an Experiment whose observed values are
identical within both arms (realistic with TEST's deterministic provider —
the same seed inputs can genuinely produce identical `views` counts across
a small sample) reports `INSUFFICIENT_VARIANCE`, not a divide-by-zero or a
fabricated p-value.

To see the `ASSIGNED_OBSERVED`/`PER_PROTOCOL_OBSERVED` populations
genuinely differ: apply a *different* `ContentSuggestion` than the one the
experimental RobotRun generated (e.g. manually create and apply a second
suggestion on the same Draft before it publishes) — `protocolDeviation`
becomes `true` on that Publication's attribution, `ASSIGNED_OBSERVED` still
counts it, and `PER_PROTOCOL_OBSERVED` excludes it; both variant's
`protocolDeviationCount` reflects it either way.

The one new dependency this phase introduces is `commons-math3` (Student's-t
distribution for the critical value and p-value) — no other statistics
library existed anywhere in the repository before. All numerical reference
values used in `WelchStatisticsTest` were computed independently with
Python + SciPy (`scipy.stats.ttest_ind(..., equal_var=False)`) during
development, never by calling the Java implementation itself — see the
Phase 14B final report for the exact script.
# Experiment decisions (Phase 14C)

Set `EXPERIMENT_DECISION_MIN_COVERAGE` (default `0.60`), `EXPERIMENT_DECISION_ATTRITION_WARNING_POINTS` (default `20`), and `EXPERIMENT_DECISION_DEVIATION_WARNING_RATE` (default `0.20`) to tune deterministic review checks. Values are startup-validated. New experiments must provide a positive `minimumPracticalEffect` with at most four decimals in their primary metric units before activation. Existing active V25 experiments retain `null` and remain readable/decidable.

Use `/experiments` to inspect Phase 14B evidence, both readiness populations, and immutable human-decision history. Recording a decision requires an explicit rationale and does not modify Robots, Personas, schedules, publishing, assignment traffic, or experiment lifecycle. POST `/api/experiments/{id}/decisions` with `{ "decision": "INCONCLUSIVE", "selectedVariantKey": null, "population": "ASSIGNED_OBSERVED", "rationale": "More observation needed", "idempotencyKey": "<uuid>" }`. Retry with the same UUID and identical request to retrieve the same record; use a new UUID for a deliberate new decision. GET `/decision-readiness`, `/decisions`, or `/decisions/{decisionId}` requires workspace membership. The V26 migration introduces no data rewrite and leaves legacy thresholds null.

## Decision applications (Phase 15A)

Use the SELECT_VARIANT decision row in `/experiments`, select a Robot linked to that Experiment, and request a preview. Apply sends only Robot intent, preview fingerprint, opaque idempotency key, and the three explicit warning confirmations; target/previous Persona, user, time, and evidence are server-derived. Preview remains available when `EXPERIMENT_DECISION_APPLICATION_ENABLED=false`, while apply and rollback return `DECISION_APPLICATION_DISABLED`. Histories are bounded to 100 rows. A rollback is allowed only while the Robot still carries the Persona set by the original application and the previous Persona is still ACTIVE (null is supported). Never reuse an old preview after editing the Robot or archiving/restoring a Persona.
## Testing diverse highlight selection

1. Import and inspect a spoken source, create a transcript, then complete a `DETERMINISTIC_V3` analysis with several candidates.
2. In Content details choose 1–5 highlights and select **Create diverse selection**.
3. Inspect selection order, original V3 source rank, excerpts, and **Why candidates were skipped**.
4. Select **Create clips for selection**. Repeating the action reuses item clip/job references rather than creating duplicates.
5. Let the Worker process `CREATE_CLIP` and automatic `INSPECT_MEDIA`; vertical derivatives remain explicit per clip.

The selector is content-derived only. Lexical Jaccard is not semantic understanding: paraphrases may evade it, different wording can express the same idea, and Whisper errors affect comparison. Diversity predicts neither engagement nor performance. A partial selection is expected when fewer candidates satisfy the versioned rules.

## Testing multi-output Robot runs

1. Configure a Robot with `TOP_DIVERSE_HIGHLIGHTS`, highlight count 1–5, and a source that can complete a strict V3 analysis.
2. For `DRAFT_ONLY`, run it and verify one frozen selection, one ordered child per selected item, and one Draft per child.
3. For `REVIEW_REQUIRED`, approve or reject children independently; sibling state must not change.
4. For `AUTO_SCHEDULE`, use a TEST account and verify `scheduledFor = base + (selectionOrder - 1) * outputSpacingMinutes`.
5. Restart the API during reconciliation and confirm IDs are reused. Repeated reconciliation must not duplicate outputs, clips, Drafts, suggestions, approvals, schedules, or publications.

Current limits: five outputs maximum, one source asset and one policy, Persona, and Experiment treatment per parent, no multi-account fan-out, and no rollback/deletion of already published content. More outputs increase media and AI compute cost.

## Testing campaign content planning locally

`campaignPlanningPolicy` is an optional field on `POST`/`PATCH /api/robots`, meaningful only alongside `highlightStrategy: "TOP_DIVERSE_HIGHLIGHTS"` (`NO_CAMPAIGN_PLAN` is the default and is a no-op — behavior is byte-identical to a Robot that predates Phase 17E).

1. `DETERMINISTIC_PLAN` needs no configuration at all: create a `TOP_DIVERSE_HIGHLIGHTS` Robot with `"campaignPlanningPolicy": "DETERMINISTIC_PLAN"` and `POST /api/robots/{id}/run`. The plan is generated and auto-applied synchronously inside the same reconciliation pass that creates the outputs — poll `GET /api/robot-runs/{id}/campaign-plans` and expect exactly one `APPLIED` revision with one item per output, `sequence` matching each output's `selectionOrder`, and a role drawn from the deterministic 1–5 mapping (documented in `docs/ARCHITECTURE.md`).
2. To exercise `AI_PLAN_FOR_REVIEW`/`AI_PLAN_AND_APPLY`, the backend's `CONTENT_AI_PROVIDER`/`CONTENT_AI_MODEL` and the Worker's `CONTENT_AI_RUNTIME`/`CONTENT_AI_ENDPOINT`/`CONTENT_AI_MODEL` must already be configured for a real Ollama model exactly as in "Testing AI content enrichment locally" above — campaign planning reuses that same provider selection and kill switch verbatim, there is no separate `DETERMINISTIC_TEST` fallback for campaign generation. Requesting an AI plan while the backend's configured provider isn't registered on the Worker fails the plan (and, since a required plan blocks the whole run, the `RobotRun` itself) with the safe, terminal `AI_PROVIDER_UNAVAILABLE`/`CAMPAIGN_PLAN_FAILED` codes rather than producing fake output — a good way to confirm the failure path without touching real infrastructure.
3. For `AI_PLAN_FOR_REVIEW`, poll until the plan reaches `READY_FOR_REVIEW`, then `POST /api/campaign-plans/{planId}/apply` or `/reject`. Applying a plan is advisory only: it never touches a `ContentSuggestion`, `RobotApproval`, or `PublishSchedule` — a Robot with `aiPolicy: "GENERATE_FOR_REVIEW"` still requires its own separate per-output suggestion review, and `AUTO_SCHEDULE` timing is completely unaffected by whether a campaign plan is even attached.
4. `POST /api/robot-runs/{runId}/campaign-plans/regenerate` supersedes the current revision and creates a new one — `GET /api/robot-runs/{runId}/campaign-plans` returns the full revision history newest-first; the superseded revision is never mutated, and any `ContentSuggestion` already generated from it keeps pointing at that historical revision rather than being retargeted.
5. With `aiPolicy` anything other than `"NO_AI"`, once a plan is `APPLIED` each output's `GENERATE_SOCIAL_COPY` suggestion consumes that output's own plan item guidance — `GET /api/content-drafts/{draftId}/suggestions` shows `promptVersion: "SOCIAL_COPY_V3_CAMPAIGN"` and the suggestion's own `campaignPlanId`/`campaignPlanRevision`/`campaignPlanItemId` fields on rows that actually used it; a Robot with `aiPolicy: "NO_AI"` still gets a fully applied campaign plan and Drafts, but never a `ContentSuggestion`, confirming applying guidance never forces AI generation on its own.
