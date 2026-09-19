# Roadmap

The repository is currently at Phase 12B. Completed phases are marked below;
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
19. **Content drafts & end-to-end content workflow (Phase 11A)** — a
   workspace-scoped `ContentDraft` bridges a source `MediaAsset` (or a
   `HighlightCandidate`) to a `Publication` as a coherent product object,
   without becoming a second media-processing system: it orchestrates the
   existing `CREATE_CLIP` / `CREATE_SOCIAL_VERTICAL` Jobs through
   `MediaAssetService` and creates Publications through `PublishingService`,
   never running FFmpeg or duplicating either service's logic itself. Durable
   `status` (DRAFT/READY/PUBLISHING/PUBLISHED/FAILED) and `workflowStage`
   (CLIP_PENDING/VERTICAL_PENDING/READY) columns on the draft row — reconciled
   idempotently, under a row lock, on every draft read — let preparation
   survive polling, refresh, and restart without an in-memory callback or a
   second derivative being queued. A candidate-originated draft always
   resolves to the 9:16 social vertical; an existing eligible asset becomes a
   draft directly. `Publication` gained an optional `contentDraftId` so a
   draft can carry more than one Publication over time (future multi-platform
   fan-out) while remaining the historical, unedited record of what was
   actually submitted — caption edits on the draft after a Publication exists
   never alter it. A failed Publication reverts the draft's display status to
   READY rather than a dead end; a failed clip/vertical derivative moves the
   draft to FAILED with a user-triggered, idempotent retry. The Content page
   gained a Drafts view alongside Assets, and "Create Draft" actions on
   eligible assets and highlight candidates, without replacing the existing
   Phase 10A direct-publish flow. *(complete)*
20. **Content scheduling & publishing calendar (Phase 11B)** — a
   workspace-scoped `PublishSchedule` lets a user schedule a READY
   `ContentDraft` for a future instant: "publish this Draft to this
   SocialAccount at this time," with the destination media, caption, and
   account fixed (snapshotted) at creation so a later Draft edit can never
   silently change what an already-scheduled post will publish. A future
   schedule reserves no Worker, Job lease, or `PUBLISH_MEDIA` Job — the
   central server owns dispatch: a `@Scheduled` poller claims due schedules
   under `SELECT ... FOR UPDATE SKIP LOCKED` (the same idiom `JobRepository`
   already uses for Job claiming, proven safe against two concurrent
   claimers with a live two-session Postgres test) and, in one transaction
   per schedule, creates a normal Publication through the existing
   `PublishingService` — no second publishing pipeline, no new Job type. If
   no Worker is online at due time, the resulting `PUBLISH_MEDIA` Job simply
   sits durably queued, exactly like any other Job, until one claims it. A
   schedule can be cancelled or rescheduled any time before dispatch;
   dispatch itself is a one-way door — once a Publication exists, the
   schedule is `DISPATCHED` and whatever happens to that Publication
   afterward (success or failure) is tracked on the Publication, never
   rewritten back onto the schedule. The Content page gained a Schedule
   action on READY/PUBLISHED Drafts and a chronological Upcoming/History
   view. *(complete)*
21. **Robots & automation foundation (Phase 11C)** — a workspace-scoped
   `Robot` is a persistent automation *policy* ("what should happen"), not a
   Worker and not a Job — it orchestrates the existing `HighlightService`,
   `ContentDraftService`, and `PublishScheduleService` exactly as a human
   using the Content page would, without a second media pipeline or a second
   publishing path. Three autonomy modes bound how far a Robot may act
   without a human: `DRAFT_ONLY` stops at a READY draft, `REVIEW_REQUIRED`
   creates a `RobotApproval` and waits for a human decision, and
   `AUTO_SCHEDULE` creates a `PublishSchedule` unattended — but only against
   the `TEST` provider; the backend hard-rejects
   `AUTO_SCHEDULE` targeting any real provider (Instagram) with
   `AUTONOMOUS_PROVIDER_NOT_ALLOWED`, so unattended posting to a real
   account is impossible regardless of configuration. A durable `RobotRun`
   audit record tracks provenance (`highlightAnalysisId` →
   `highlightCandidateId` → `contentDraftId` → `publishScheduleId`) and is
   reconciled idempotently under a row lock, the same pattern `ContentDraft`
   established in Phase 11A. A central `@Scheduled` poller claims due Robots
   under `SELECT ... FOR UPDATE SKIP LOCKED` — the same idiom `JobRepository`
   and `PublishSchedule` already use — and reserves no Worker or Job before
   real media work exists. Per-robot Pause and a global
   `ROBOT_AUTOMATION_ENABLED` kill switch stop new automation without
   touching Jobs, Drafts, or Schedules already in flight; a DB-level partial
   unique index enforces at most one active `RobotRun` per Robot, and
   duplicate-source protection allows at most one successful run per
   Robot+source asset by default. Sources are restricted to existing,
   already-imported assets — no arbitrary URL ingestion or scraping. The
   Content page's Provenance panel now shows when a draft was created by a
   Robot run. *(complete)*
22. **Dynamic content sources & selection policies (Phase 11D)** — a
   workspace-scoped `ContentSource` (type `MEDIA_LIBRARY` only) is a
   controlled pool of explicitly associated workspace `MediaAsset`s a Robot
   may select from, instead of being permanently tied to one fixed asset —
   never a URL feed, an RSS reader, or any form of autonomous web access.
   `Robot` gained a `sourcePolicy` (`EXISTING_ASSET`, the unchanged Phase
   11C shape, or `CONTENT_SOURCE`) plus a deterministic `selectionPolicy`
   (`OLDEST_UNPROCESSED`/`NEWEST_UNPROCESSED`, ordered by when an asset
   joined that source, never AI ranking or prediction of any kind). Only
   `ORIGINAL` assets may become source input — a generated clip or vertical
   derivative can never recursively feed back in. Selection happens in one
   PostgreSQL query (eligibility + ordering + per-Robot exclusion of every
   asset that Robot has ever selected before, regardless of that run's
   outcome), with a DB-level partial unique index as defense in depth so
   the same Robot can never dynamically select the same asset twice while a
   different Robot sharing the same source freely may. An empty-but-active
   source produces a real, auditable `RobotRun` terminal with
   `NO_ELIGIBLE_SOURCE`; a paused source rejects before any run is even
   created. `RobotRunOrchestrator` needed exactly one change — reading the
   run's own selected `sourceAssetId` instead of the Robot's fixed one — so
   the entire downstream highlight/draft/schedule pipeline stays completely
   unaware a ContentSource was ever involved. The Content page gained a
   Sources tab and an "Add to Source" action on original assets; the Robots
   page's create form gained a source-type switch with a human-readable
   selection-policy label. *(complete)*
23. **AI content enrichment foundation (Phase 12A)** — a workspace-scoped
   `ContentSuggestion` lets a human generate AI-drafted hook/caption/hashtags
   for a READY `ContentDraft`, but AI output is always a suggestion, never an
   authoritative mutation: it cannot silently change a Draft, publish, create
   a `PublishSchedule`, or bypass Robot approval — Robots do not auto-generate
   suggestions in this phase. A narrow, provider-neutral
   `ContentEnrichmentProvider` abstraction (Worker-side) mirrors the
   `HighlightAnalyzer` map pattern from Phase 7B2, with a `DETERMINISTIC_TEST`
   provider for tests/local fallback and a real `OllamaContentEnrichmentProvider`
   for local LLM generation — no domain service depends on a vendor SDK
   directly. Unlike the highlight analyzer, prompt construction is centralized
   on the backend (`SocialCopyPromptBuilder`, versioned as `SOCIAL_COPY_V1`)
   for testability and reproducibility; the frozen prompt travels to the
   Worker in the generation authorization response, which also gives
   suggestion output natural snapshot semantics — nothing later can change
   what was actually asked. A bounded `ContentEnrichmentContextBuilder`
   gathers only draft/source/highlight metadata and transcript segments
   overlapping the selected clip (with padding), never whole DB entities or
   unbounded transcript text, and the prompt explicitly treats that context as
   untrusted data, not instructions. Generation runs as a `GENERATE_SOCIAL_COPY`
   Job through the existing distributed Job/Worker infrastructure (claim,
   lease, retry, scheduling, metrics) — no second AI job system — so the
   backend never needs the provider secret; only the Worker's environment
   does. Structured provider output is validated into a controlled schema
   (bounded hook/caption/hashtag/short-title lengths, normalized hashtags)
   before being persisted as READY; invalid output is rejected as a distinct
   terminal `AI_OUTPUT_REJECTED` failure, separate from transient provider
   failures which use the normal bounded Job retry path. A deterministic
   SHA-256 input fingerprint over the generation inputs detects staleness at
   Apply time — if a human has edited the Draft since generation, Apply is
   rejected with `SUGGESTION_STALE` rather than silently overwriting the
   human edit. Regenerating always creates a new, independent
   `ContentSuggestion` row; Apply is an explicit, transactional, idempotent
   human action that composes hook/caption/hashtags into `Draft.caption`.
   The Content page gained an AI Content section per Draft with durable,
   polled suggestion history (not a spinner holding one request open),
   language/tone controls, and Apply/Discard/Regenerate actions. *(complete)*
24. **Persona & Brand Voice (Phase 12B)** — a workspace-scoped `Persona` is
   reusable, structured editorial configuration (audience/voice/style/avoid/
   hashtag-guidance/example-copy, each field explicitly bounded) a human may
   optionally select when generating a `ContentSuggestion` — never a Robot,
   an AI provider, a raw system prompt, a social account, or a Worker, and
   never exposed as free-form prompt syntax to the user. `Persona` reuses
   the exact Phase 12A `SuggestionLanguage`/`SuggestionTone` enums (no
   parallel semantics) as its optional `defaultLanguage`/`defaultTone`;
   generation precedence is explicit and backend-authoritative — an explicit
   request value always wins, otherwise the selected Persona's default
   applies, otherwise Phase 12A's own default (AUTO/NEUTRAL). Persona
   selection is fully optional and additive: omitting it reproduces Phase
   12A behavior exactly. The critical design property is the immutable
   Persona *snapshot*: at generation time, the selected Persona's editorial
   fields are copied once onto the `ContentSuggestion` itself (flat columns,
   not a live reference) and the input fingerprint used for stale-Apply
   detection is derived only from that frozen snapshot — so editing or
   archiving a Persona can never retroactively change an existing
   suggestion's meaning, its historical display, or cause a previously-valid
   Apply to start failing; only an actual Draft edit still triggers
   `SUGGESTION_STALE`. Persona-aware generation introduces prompt version
   `SOCIAL_COPY_V2` (used uniformly, Persona or not) with a clearly
   delimited, explicitly-untrusted `EDITORIAL_PERSONA` section that can
   never override structured-output rules, safety rules, or the
   source-grounding requirement — source context truth always wins over
   Persona style, and Persona `exampleCopy` is explicitly marked
   style-reference-only so the model cannot lift facts from it. An archived
   Persona cannot be selected for new generation but never invalidates
   suggestions already generated from it. Robots do not reference or use a
   Persona in this phase (deliberately deferred, not dead configuration).
   The Worker/Job/prompt-transport architecture from Phase 12A is completely
   unchanged — a Persona reaches the Worker only as already-rendered text
   inside the same opaque `promptText` channel. A first-class Personas page
   (list/create/edit/archive/restore) and an extended Content page AI
   section (Persona selector, defaults pre-fill, snapshot name shown on
   every suggestion card even after a rename or archive) round out the
   phase. *(complete)*
25. **FFmpeg processing** — richer automated video processing pipelines.
26. **Additional platform integrations** — TikTok, YouTube, or other real
   platforms behind the same provider-boundary pattern Instagram
   established in Phase 10B.
27. **AI content automation** — Robot-driven autonomous AI generation/
   auto-apply, Persona-to-SocialAccount assignment, multi-persona blending,
   AI-generated Personas, and engagement-driven voice tuning — all
   explicitly out of scope through Phase 12B, building on the Persona and
   suggestion model it established.
28. **Analytics / revenue** — performance analytics and revenue tracking.
