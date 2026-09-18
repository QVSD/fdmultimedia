# workers

The standalone Java worker agent in `java-agent/` registers
the current machine with the Spring control plane, sends periodic heartbeats,
polls for work, executes the safe `SYSTEM_TEST` job type, and imports direct
HTTP/HTTPS media files through `IMPORT_MEDIA`. When FFprobe is available, it
also inspects stored originals through `INSPECT_MEDIA`. When FFmpeg is
available, it creates controlled clip derivatives through `CREATE_CLIP` and
fixed 1080x1920 social vertical derivatives through `CREATE_SOCIAL_VERTICAL`.
It also advertises `ANALYZE_HIGHLIGHTS`, which uses a deterministic local
analyzer and does not require FFmpeg, FFprobe, or any AI provider.
When FFmpeg and a configured local Whisper-compatible CLI are available, it also
advertises `TRANSCRIBE_MEDIA`. It always advertises `PUBLISH_MEDIA` for the
`TEST` provider, backed by a deterministic, explicitly non-real publisher: it
downloads the source asset through a short-lived presigned URL, verifies it is
non-empty and (when provided) checksum-matches, and returns a deterministic
`test-pub-<publicationId>` provider id. It never contacts Instagram, TikTok, or
any other real platform for TEST.

As of Phase 10B, the agent can also *drive* real Instagram publishing when the
operator explicitly opts this specific machine in with
`WORKER_INSTAGRAM_PUBLISHING_ENABLED=true` — but even then it never downloads
source media or holds an Instagram credential. All credential-bearing Graph
API calls stay on the backend; the worker only polls a narrow endpoint in a
bounded loop and stops once the backend reports the publish finished or
failed. See [ARCHITECTURE.md](../docs/ARCHITECTURE.md#instagram-publishing-phase-10b)
for the full design and why the Worker was deliberately not given a token.

The agent persists a random installation UUID locally and uses that as the
machine identifier. It does not use MAC addresses or other hardware IDs.
Registration reports static machine metadata such as OS, architecture, logical
CPU cores, total memory, optional GPU metadata, and agent version. Heartbeats
report dynamic telemetry when the JVM/OS exposes it cheaply: CPU load,
available memory, JVM heap, active job count, and current capability snapshots.
Unavailable telemetry is sent as null/omitted; no subprocess benchmarks are run
per heartbeat.

Build and test:

```bash
cd java-agent
../../apps/api-spring/mvnw -f pom.xml test
../../apps/api-spring/mvnw -f pom.xml package
```

Run against the local Docker stack:

```bash
FDM_API_BASE_URL=http://localhost:8080/api \
FDM_WORKER_TOKEN=11111111-1111-4111-8111-111111111111.dev_worker_secret_change_me \
java -jar target/worker-agent-0.1.0-SNAPSHOT.jar
```

Optional runtime tuning:

```bash
FDM_WORKER_NAME=dragos-laptop \
FDM_WORKER_HEARTBEAT_SECONDS=10 \
FDM_WORKER_JOB_POLL_SECONDS=3 \
WORKER_MAX_ACTIVE_JOBS=1 \
FDM_WORKER_ID_FILE=.fdm-worker-id \
FFPROBE_PATH=ffprobe \
FFMPEG_PATH=ffmpeg \
TRANSCRIPTION_RUNTIME=WHISPER_CLI \
TRANSCRIPTION_COMMAND=whisper \
TRANSCRIPTION_MODEL=base \
java -jar target/worker-agent-0.1.0-SNAPSHOT.jar
```

The agent loop is:

1. register or update this installation
2. heartbeat in a dedicated loop
3. poll `POST /api/worker-agent/jobs/claim`
4. start and execute a claimed `SYSTEM_TEST`, `IMPORT_MEDIA`, `INSPECT_MEDIA`,
   `CREATE_CLIP`, `CREATE_SOCIAL_VERTICAL`, `ANALYZE_HIGHLIGHTS`,
   `TRANSCRIBE_MEDIA`, or `PUBLISH_MEDIA`
5. report completion or failure

When no jobs exist, polling backs off using `FDM_WORKER_JOB_POLL_SECONDS`.
Temporary server/network failures are logged and retried on the next poll.
`WORKER_MAX_ACTIVE_JOBS` defaults to `1`. The current Java agent is still
single-job-at-a-time, so it avoids claim polling while its local active job
count is at capacity. The API also records that capacity and uses fresh
heartbeat telemetry as a server-side scheduling guard.

Phase 9C does not alter the Worker protocol or claim behavior. The server records
one compact decision only after a real claim, in the same transaction as job
assignment. Compute exposes current capacity alongside workspace-scoped recent
execution history; it does not assign a global Worker rank or speed score.

`SYSTEM_TEST` accepts only a bounded message and duration. It never executes
shell commands, video processing, AI workloads, browser automation, publishing,
or arbitrary user-provided code. See [docs/ROADMAP.md](../docs/ROADMAP.md) and
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for later phases and the rule
that a worker is interchangeable compute, never tied 1:1 to a Robot.

`IMPORT_MEDIA` is also controlled. The worker:

1. asks the API for import authorization
2. validates the source URL and every redirect target
3. streams the download to a temporary file with configured timeouts and size
   limits
4. computes SHA-256 and basic content metadata
5. uploads the file through a short-lived presigned PUT URL
6. reports completion or a classified failure
7. deletes the temporary file

Permanent MinIO/S3 credentials stay in the API; the worker receives only
short-lived upload URLs for the exact object key generated by the server. The
worker renews the job lease while an import is running so legitimate downloads
do not expire, but abandoned imports can still be recovered by the normal job
lease mechanism.

`INSPECT_MEDIA` is enabled only when `ffprobe -version` succeeds at startup.
Without FFprobe, the agent still registers, heartbeats, imports media, and runs
system tests, but it does not advertise the inspection capability during claim.
With FFprobe available, the worker:

1. asks the API for inspection authorization
2. downloads the already stored original through a short-lived presigned GET URL
3. runs FFprobe read-only with fixed arguments and no shell
4. reports duration, resolution, codecs, container format, frame rate, bitrate,
   and stream presence
5. deletes the temporary file

`CREATE_CLIP` is enabled only when `ffmpeg -version` succeeds at startup.
Without FFmpeg, the worker still registers, heartbeats, imports, inspects when
FFprobe is present, and runs system tests, but it does not advertise
`CREATE_CLIP` or `CREATE_SOCIAL_VERTICAL` during claim. With FFmpeg available,
the worker:

1. asks the API for clip authorization
2. downloads the source asset through a short-lived presigned GET URL
3. runs FFmpeg with fixed arguments and no shell
4. uploads a new MP4 through a short-lived presigned PUT URL
5. reports checksum, size, and output format
6. deletes source and output temporary files

The source object is never modified. The API creates the output asset and
server-derived storage key before the job runs, so retries target the same
derived asset instead of creating duplicates. Users cannot supply raw FFmpeg
arguments; the first encoding profile is H.264 video, AAC audio, MP4 container,
and timing is interpreted as `[startMs, startMs + durationMs)`.

`CREATE_SOCIAL_VERTICAL` uses the same FFmpeg installation and storage flow,
but does not accept timing, dimensions, crop coordinates, or raw filter input.
The API creates one output asset with `derivationType=SOCIAL_VERTICAL`, and the
worker runs a fixed center-crop filter:

```text
scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920
```

The output is MP4/H.264 at exactly 1080x1920. AAC audio is produced when the
source has audio; video-only sources remain video-only. Audio-only sources are
rejected by the server because the preset requires video. The source object is
never modified, retries target the same derived asset/storage key, and the
normal automatic inspection job verifies the derivative afterward. AI or
subject-aware reframing is intentionally deferred.

`ANALYZE_HIGHLIGHTS` is enabled by default because Phase 7A uses only metadata
provided by the API. The worker:

1. asks the API for analysis authorization
2. receives the analysis ID, asset ID, duration, candidate limits, and analyzer
   identity
3. runs `DeterministicHighlightAnalyzer`
4. reports structured candidate intervals

The deterministic analyzer proposes bounded intervals around fixed timeline
positions and labels them `DETERMINISTIC_V1`. It does not download source
media, execute shell commands, invoke FFmpeg, call OpenAI/Anthropic/Gemini, run
local LLMs, transcribe audio, or use vision models. The server validates and
ranks all candidate output before persistence. A candidate is only a
recommendation; clip media is created later only when a user explicitly chooses
**Create Clip**, which goes through the normal `CREATE_CLIP` pipeline.

`TRANSCRIBE_MEDIA` is enabled only when FFmpeg is available and the configured
local transcription runtime is available. `TRANSCRIPTION_RUNTIME=WHISPER_CLI`
uses the Python `whisper` CLI contract:

```text
whisper <audio.wav> --model <model> --output_format json --output_dir <dir> --fp16 False
```

`TRANSCRIPTION_RUNTIME=WHISPER_CPP` uses `whisper-cli` with a local
`ggml-*.bin` model:

```text
whisper-cli -m <model.bin> -f <audio.wav> -l auto -oj -of <output-base> -np
```

The worker checks command availability with `--help`; the whisper.cpp runtime
also requires the configured model path to exist. The worker:

1. asks the API for transcription authorization
2. downloads the assigned stored media through a short-lived presigned GET URL
3. extracts mono 16 kHz PCM WAV audio with controlled FFmpeg arguments
4. invokes the configured local Whisper-compatible CLI without a shell
5. parses bounded structured JSON segments
6. reports detected language and timestamped segments
7. deletes source, audio, and provider temporary files

The worker never receives permanent object-storage credentials and never stores
transcripts in job results only; the API persists `MediaTranscript` and
`TranscriptSegment` rows transactionally after validating provider output.

`ANALYZE_HIGHLIGHTS` supports multiple analyzer identities. `DETERMINISTIC_V1`
is always available and remains the default. `TRANSCRIPT_SEMANTIC_V1` is
advertised only when a configured semantic provider is reachable at startup.
The first provider uses local Ollama over HTTP:

```text
SEMANTIC_HIGHLIGHT_RUNTIME=OLLAMA
SEMANTIC_HIGHLIGHT_ENDPOINT=http://localhost:11434
SEMANTIC_HIGHLIGHT_MODEL=llama3.2:1b
SEMANTIC_HIGHLIGHT_TIMEOUT_SECONDS=180
```

The worker sends transcript-derived windows to the provider and asks for
structured candidate references. It does not send source media, presigned URLs,
storage keys, worker credentials, MinIO credentials, or raw browser-provided
commands/prompts. The API filters job claims by supported analyzer type and
validates that returned semantic candidates are grounded in transcript segment
timing before persistence.

`PUBLISH_MEDIA` for the `TEST` platform is always advertised; it requires no
external tool and no configuration, backed by the deterministic
`TestPublishingProvider`. `PublishMediaExecutor` branches on
`authorization.platform()` after the initial authorization call:

**TEST** (unchanged since Phase 10A):

1. asks the API for publication authorization (asset, account, platform,
   caption, and a short-lived presigned GET URL for the source asset)
2. downloads the source asset through that presigned URL with bounded
   streaming and a configured maximum size
3. hands the downloaded file to `TestPublishingProvider`
4. reports the provider's result (`providerRequestId`, `providerPublicationId`,
   `publishedAt`) or a classified failure
5. deletes the temporary file

`TestPublishingProvider` never contacts Instagram, TikTok, or any other real
platform. It validates the downloaded media is non-empty and, when an expected
checksum is supplied, that it matches, then returns
`test-pub-<publicationId>` as the provider publication id. Because that id is
derived only from the Publication id, retries of the same Publication are
naturally idempotent: the same provider id is returned every time, matching
the backend's idempotency contract.

**INSTAGRAM** (Phase 10B, only when `WORKER_INSTAGRAM_PUBLISHING_ENABLED=true`):

1. asks the API for publication authorization — the response's `downloadUrl`
   is `null` for this platform; the worker never downloads media itself
2. repeatedly calls `POST /worker-agent/publications/{jobId}/instagram/drive`
   (bounded: a bit longer than the backend's own default processing timeout,
   as a last-resort safety net; the backend's own timeout normally fires
   first), sleeping briefly between calls while the existing lease-renewal
   thread keeps the job lease alive independently
3. stops as soon as the response reports anything other than `IN_PROGRESS` —
   the backend has already finalized the Job and Publication as part of that
   same call, so there is nothing left for the worker to report

Every actual Instagram Graph API call (container creation, status polling,
the final publish call) happens inside that one backend endpoint, using a
credential decrypted only on the backend for exactly that call. See
[ARCHITECTURE.md](../docs/ARCHITECTURE.md#instagram-publishing-phase-10b) for
the full protocol, the reconciliation strategy that avoids a duplicate real
post on retry, and why this trust boundary was chosen over giving the Worker
a token the way `TestPublishingProvider` has one.

## Telemetry and scheduling inputs

The worker reports active jobs as the real number it is executing. The current
Java agent is single-job-at-a-time, so this is normally `0` or `1`; the field is
designed so future agents can report higher concurrency. Telemetry collection
failure is logged locally and does not stop heartbeats or job execution.

The control plane stores the latest telemetry only, with a freshness window
defined server-side. It also records per-attempt execution metrics from server
timestamps when jobs succeed, fail, or recover after lease expiry. Phase 9B
uses `TELEMETRY_AWARE_V1`: claims still use the PostgreSQL
`FOR UPDATE SKIP LOCKED` queue path, but the API locks a bounded compatible
candidate window and chooses a job for the polling worker using fresh capacity,
memory/CPU telemetry when available, and recent execution history after enough
samples exist. Missing telemetry falls back to FIFO; no predictive scheduler,
GPU scheduler, or autoscaling is implemented yet.
