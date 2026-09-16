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

Domain logic isn't implemented yet, but the package layout under
`com.fdmultimedia.api` (`auth`, `users`, `workspaces`, `accounts`, `robots`,
`assets`, `jobs`, `workers`, `publishing`, `analytics`, `revenue`, `shared`)
is where it should land. See [ARCHITECTURE.md](ARCHITECTURE.md) for what
each package is for.

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
