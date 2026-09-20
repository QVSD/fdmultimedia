# Roadmap

The repository is currently at Phase 14B. Completed phases are marked below;
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
25. **Robot AI Enrichment (Phase 12C)** — Robots may now automatically
   invoke Phase 12A/12B AI content generation, on a new `RobotAiPolicy` axis
   (`NO_AI` / `GENERATE_FOR_REVIEW` / `GENERATE_AND_APPLY`) kept strictly
   independent of the existing `RobotAutonomyMode` axis — the two are never
   collapsed into one switch. `NO_AI` reproduces Phase 11C/11D behavior
   exactly. `GENERATE_FOR_REVIEW` creates exactly one automatic
   `ContentSuggestion` and parks the RobotRun in a new `WAITING_FOR_AI_REVIEW`
   state — a human review gate deliberately distinct from the pre-existing
   publishing-approval `WAITING_FOR_REVIEW` gate — until a human Applies or
   Discards it through the ordinary suggestion endpoints, after which the
   Robot resumes its existing autonomy unattended. `GENERATE_AND_APPLY`
   auto-applies through the exact same human Apply path (fingerprint/staleness
   checks included) before continuing. A Robot may optionally reference a
   Persona (workspace-scoped, validated ACTIVE at both configure time and
   generation time); `RobotRun` snapshots the AI policy and Persona
   *identity* at run-creation time so a mid-run Robot edit never redirects
   an in-flight run, while the Persona's editorial *content* is still
   resolved fresh (and re-validated ACTIVE) at the moment generation
   actually happens. Every automatically generated suggestion carries an
   explicit `origin` (`MANUAL` vs `ROBOT`) and, for `ROBOT`, the originating
   `robotRunId` — no longer inferred from the Draft's own Robot provenance,
   which was a latent conflation fixed in this phase. Reconciliation reuses
   the existing Phase 11C row-locked (`FOR UPDATE SKIP LOCKED`) bounded
   poller unchanged, giving idempotent, multi-instance-safe progress (at
   most one automatic suggestion and one Job per run) for free, and a
   `content_suggestions_one_robot_suggestion_per_run` unique index as
   defense in depth. AI failure, an archived Persona, a discarded
   suggestion, or the global `CONTENT_AI_ENABLED` kill switch all fail the
   RobotRun safely with a bounded failure code — never an infinite wait,
   never a Robot-level retry (the existing Job owns bounded provider
   retries). AUTO_SCHEDULE's real-provider gate (TEST only, Instagram still
   rejected) is unaffected by AI policy. *(complete)*
26. **FFmpeg processing** — richer automated video processing pipelines.
27. **Additional platform integrations** — TikTok, YouTube, or other real
   platforms behind the same provider-boundary pattern Instagram
   established in Phase 10B.
28. **Further AI content automation** — automatic AI regeneration, Robot
   A/B testing of multiple suggestions, analytics/engagement-driven prompt
   or Persona optimization, AI-assisted source or highlight selection,
   multiple Personas per run or Persona blending, and AI-generated
   Personas — all explicitly out of scope through Phase 12C, which covers
   only a single bounded automatic-or-reviewed generation per RobotRun.
29. **Publication analytics foundation (Phase 13A)** — immutable normalized
   TEST publication metrics, bounded restart-safe collection, and immutable
   publication-time attribution. Instagram insights require verified account
   permissions; no unsupported metrics are fabricated. No strategy optimization.
   *(complete)*
30. **Publication analytics dashboard (Phase 13B)** — read-only comparison,
   trend, and breakdown views over the Phase 13A snapshots and attribution
   rows: a workspace-scoped summary/trend/breakdown/filter-options query
   surface with date-range, observation-window (latest/24h/72h/7d),
   provider/Robot/Persona/ContentSource/origin/AI-usage filtering, explicit
   coverage reporting (eligible vs. too-young vs. missing-snapshot) alongside
   every aggregate, and bounded (100-group) breakdowns. Adds a
   publication-time ContentSource name snapshot alongside the existing
   Robot/Persona ones. No ranking, scoring, or optimization — purely
   descriptive aggregation of already-immutable data. *(complete)*
31. **Deterministic performance insights (Phase 13C)** — a deterministic,
   non-AI `PERFORMANCE_INSIGHTS_V1` engine reuses Phase 13B's exact
   observation-window/cohort semantics to produce bounded, evidence-bound
   descriptive comparisons (`HIGHER_OBSERVED`/`LOWER_OBSERVED`/
   `SIMILAR_OBSERVED`, never "better"/"worse"/"winner") across ORIGIN,
   AI_USAGE, Robot, Persona, ContentSource, and provider segments, gated by
   configurable minimum sample size, minimum coverage, and a material-
   difference threshold — below any of which the engine reports
   `INSUFFICIENT_SAMPLE`/`LOW_COVERAGE`/`TOO_YOUNG` instead of a directional
   claim. A bounded automatic `/insights` endpoint covers only ORIGIN and
   AI_USAGE (each has exactly two natural buckets); Robot/Persona/
   ContentSource/provider comparisons are explicit (`/insights/compare`)
   rather than an implicit leaderboard. Every result carries a causality
   disclaimer, is purely descriptive, and never mutates a Robot, Persona,
   ContentSource, schedule, or AI policy — recommendations are limited to
   asking a human to collect more data, wait for maturity, or review content
   manually. *(complete)*
32. **Controlled experiments & A/B testing foundation (Phase 14A)** — a
   first-class `Experiment` compares exactly two frozen variants (A/B) of one
   factor at a time — PERSONA only in this phase, AI_POLICY deliberately
   deferred to avoid ambiguity with the human-review gate. Assignment is a
   `RobotRun`-scoped, database-locked, deterministic least-assigned balance
   algorithm, always durable before the treatment is ever consumed and never
   dependent on any performance outcome. The Persona treatment freezes at
   Experiment activation and never re-reads the live Persona again, so an
   edit or archive afterward cannot change an in-flight or historical
   run's meaning. Provenance reaches `ContentSuggestion` and the existing
   immutable `PublicationAttribution` snapshot, including a descriptive
   protocol-deviation flag for a human-edited final publication. A
   descriptive-only outcome endpoint reuses Phase 13B's exact
   snapshot-selection semantics at the experiment's own fixed observation
   window/metric — no winner, no significance testing, no automatic
   Robot/Persona/schedule mutation. *(complete)*
33. **Statistical experiment analysis (Phase 14B)** — a deterministic,
   non-AI `EXPERIMENT_ANALYSIS_V1` engine computes Welch's unequal-variance
   t-test (mean difference, 95% CI, two-sided p-value, Hedges' g) over a
   canonical one-row-per-`ExperimentAssignment` dataset — a deterministic
   Publication selection rule (earliest `published_at`, then id) so a
   duplicated Publication can never inflate a sample, and Phase 13B's exact
   snapshot-selection semantics reused verbatim for the Experiment's own
   fixed observation window. Two populations are always shown side by side,
   `ASSIGNED_OBSERVED` and `PER_PROTOCOL_OBSERVED` (excludes protocol
   deviations), never merged or hidden. Below a configurable minimum sample
   or with zero pooled variance, only descriptive statistics are shown —
   never a fabricated CI/p-value. No `winner`/`recommendedVariant` field
   exists anywhere; an active Experiment carries a standing interim-analysis
   warning, and mixed analytics providers block inferential pooling. No new
   migration, no persisted result — every analysis is computed on demand
   from already-immutable data. *(complete)*
34. **Revenue tracking / automatic optimization** — separate future work, not
   started.
