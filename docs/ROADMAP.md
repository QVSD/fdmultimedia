# Roadmap

The repository is currently at Phase 10B. Completed phases are marked below;
later phases are planned high-level direction and intentionally do not go into
implementation detail before they are started.

1. **Foundation** — Angular + Spring Boot + PostgreSQL + RabbitMQ +
   Nginx + Docker Compose, modular monolith skeleton, health checks.
   *(complete)*
2. **Users / workspaces** — accounts, workspaces/tenancy, authentication.
   *(complete)*
3. **Worker registration** — workers can register themselves with the
   control plane. *(complete)*
4. **Distributed jobs** — users can create safe `SYSTEM_TEST` jobs, workers
   claim them atomically through PostgreSQL, execute them once, and report
   durable results with leases and bounded retries. *(complete)*
5. **Media assets / direct video import** — users can submit direct HTTP/HTTPS
   media file URLs, the API creates `MediaAsset` + `IMPORT_MEDIA`, workers
   stream bounded downloads, enforce SSRF protections, checksum content, upload
   through presigned object-storage URLs, and mark assets READY. *(complete)*
6. **Media inspection** — API creates `INSPECT_MEDIA` after import success;
   FFprobe-capable workers read stored originals and persist duration,
   resolution, codec, container, frame-rate, bitrate, and stream metadata
   without transforming media. *(complete)*
7. **Clip derivatives** — users create bounded clips from READY + INSPECTED
   assets; FFmpeg-capable workers produce immutable derived MP4 assets and
   automatic inspection runs on the result. *(complete)*
8. **Social vertical preset** — users create immutable 1080x1920 center-cropped
   derivatives from READY + INSPECTED video assets. *(complete)*
10. **Highlight candidates** — create persisted highlight analyses and
   deterministic candidate intervals that users can turn into existing
   `CREATE_CLIP` jobs. *(complete)*
11. **Speech transcription** — persisted `MediaTranscript` and
   `TranscriptSegment` data through `TRANSCRIBE_MEDIA`, using a local
   configurable transcription provider. *(complete)*
12. **Semantic highlight analysis** — add `TRANSCRIPT_SEMANTIC_V1` behind the
   existing `ANALYZE_HIGHLIGHTS` job using persisted transcript segments and a
   local provider boundary. Media, storage credentials, and raw commands are
   not sent to the provider. *(complete)*
13. **Product UI consolidation** — make Content the primary workflow surface,
   hide unfinished placeholder areas from primary navigation, replace
   engineering tables with asset summaries/details, and present transcripts,
   highlights, clips, and vertical derivatives in product language. *(complete)*
14. **Worker telemetry and scheduling foundation** — collect current Worker
   capacity telemetry, capability snapshots, and per-attempt execution metrics
   while preserving capability-first FIFO claiming. *(complete)*
15. **Telemetry-aware scheduler / load balancing foundation** — use fresh
   capacity telemetry, bounded execution history, memory/CPU signals, and
   starvation protection to choose among locked compatible queued jobs without
   replacing the PostgreSQL atomic claim model. *(complete)*
16. **Scheduling observability and performance feedback** — persist successful
   claim explanations atomically, expose bounded workspace aggregates, and show
   queue/performance history without changing scheduler behavior. *(complete)*
17. **Social accounts & publishing foundation (Phase 10A)** — workspace-scoped
   `SocialAccount` (metadata only, no credential columns), a `Publication`
   state machine (PENDING → PUBLISHING → PUBLISHED/FAILED/CANCELLED) with
   durable per-attempt `PublishingAttempt` history, and a new `PUBLISH_MEDIA`
   job type that flows through the existing distributed Job/worker/scheduling
   infrastructure unchanged. A deterministic, explicitly non-real `TEST`
   publishing provider proves the end-to-end pipeline (presigned asset access,
   idempotent retries, stale-worker-safe completion) without any real
   Instagram/TikTok integration. *(complete)*
18. **Instagram official publishing integration (Phase 10B)** — real
   Instagram account connection via Meta's official "Instagram API with
   Instagram Login" OAuth flow (hashed, single-use, expiring server-side
   state), an encrypted (AES-256-GCM) server-side credential store with no
   plaintext fallback, and real video/Reels publishing driven by a bounded
   backend orchestrator that a Worker only polls — the Worker never holds a
   provider credential, whether or not it is opted in to drive Instagram
   jobs. Container-id-based reconciliation avoids duplicate real posts on
   retry; provider-aware capability gating (a hard filter in the Job claim
   SQL) stops an unconfigured Worker from ever claiming an Instagram job;
   and a token-scoped, self-expiring public media delivery endpoint lets
   Meta fetch source video without making the private MinIO bucket public.
   Disabled by default; TEST keeps working with none of it configured.
   *(complete — see docs/ARCHITECTURE.md for the full design and the Phase
   10B final report for Level A/B acceptance status)*
19. **FFmpeg processing** — richer automated video processing pipelines.
20. **Additional platform integrations** — TikTok, YouTube, or other real
   platforms behind the same provider-boundary pattern Instagram
   established in Phase 10B.
21. **AI content** — AI-assisted content creation.
22. **Analytics / revenue** — performance analytics and revenue tracking.
