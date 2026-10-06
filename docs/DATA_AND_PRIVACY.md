# Data, Privacy and Retention (v0.1.0-rc1)

This document states what the platform stores and how long, as it behaves today. It is a description, not a compliance claim: the release makes no GDPR, SOC 2 or similar assertion, and a controller deploying it for real people remains responsible for its own lawful basis, notices, retention schedule and data-subject processes.

## What is stored, and where

| Data | Store | Notes |
| --- | --- | --- |
| Users: e-mail, display name, BCrypt password hash, enabled flag, workspace role | PostgreSQL | No plaintext password anywhere. No profile data beyond this. |
| Browser sessions and CSRF state | PostgreSQL (`spring_session*`) | Idle timeout 30 minutes by default; expired rows are removed every minute. Logout invalidates the session. A database restore intentionally restores no live sessions. |
| Worker credentials | PostgreSQL | BCrypt hash of the secret only. |
| Media files (imported originals, clips, social verticals) | Object storage (private bucket) | Reachable only through short-lived presigned URLs (5 minutes for reads by default). |
| Media metadata (source URL, filename, checksum, codecs, duration) | PostgreSQL | The source URL is stored as given. |
| Transcripts and segments | PostgreSQL | Full transcript text of imported media. Treat as content, not metadata. |
| Highlight analyses and candidates, selections | PostgreSQL | Derived from transcripts; include bounded transcript excerpts. |
| Personas, Robots, content drafts, campaign plans and copy | PostgreSQL | Authored or generated editorial content (captions, hooks, hashtags). |
| Publications, schedules, provider request/publication identifiers, attempts | PostgreSQL | Identifiers returned by Instagram or TikTok, or `test-*` identifiers for TEST publishing. |
| Social account credentials (Instagram/TikTok access tokens) | PostgreSQL, encrypted with AES-256-GCM | Key from `SOCIAL_CREDENTIAL_ENCRYPTION_KEY`; only the API ever holds a provider token, never the Worker. |
| Performance analytics, experiments, adaptive-learning evidence | PostgreSQL | Metrics fetched from the provider, experiment assignments, proposals, safety evaluations and memory. |
| Job history, scheduling decisions, Worker telemetry | PostgreSQL | Operational; decision rows are pruned after 30 days by default. |
| Operations scheduler status and incidents | PostgreSQL | Resolved incidents are pruned after 30 days, per-replica scheduler status after 7. |
| Logs | Container stdout | Contain operational events and user e-mail on login/logout lines; no passwords, tokens, cookies, presigned URLs or media. |

Not stored: payment data, government identifiers, passwords in clear, browsing history, device identifiers beyond a random Worker installation id.

## Third parties that may receive data

- **Instagram / TikTok** (only when enabled and a human publishes or schedules): the video and caption of the publication, through the official APIs. TEST publishing contacts nobody.
- **Ollama** (only when the operator runs it): transcript windows and prompts go to the operator's own Ollama endpoint; no media is sent.
- **The source of an import URL** sees the Worker's request.

## Deletion

The application currently has no delete function for users, workspaces, media, transcripts, drafts, publications, Personas or Robots (Personas are archived, Robots paused or disabled, a content source can drop an asset link, a social account can be disconnected). Removing personal or media data therefore means an operator action on PostgreSQL and the bucket (delete the rows and objects, in that order, then run `VACUUM`), and any copies in backups remain until those backups expire. Provider posts must be removed on the provider. This is a known limitation of the release candidate and the main item to resolve before storing data of third parties.

## Retention

| Item | Retention |
| --- | --- |
| Media, transcripts, drafts, publications, analytics, adaptive evidence | Kept indefinitely (no automatic deletion) |
| Sessions | 30 minutes idle by default (`SESSION_TIMEOUT`) |
| Scheduling decisions | 30 days (`SCHEDULING_DECISION_RETENTION_DAYS`) |
| OAuth state rows | Short-lived, pruned when expired |
| Resolved operations incidents / scheduler status | 30 days / 7 days (`app.operations.*`) |
| Container logs | Governed by the container runtime (Docker's `json-file` driver has no rotation by default); set `max-size` and `max-file` or ship logs elsewhere |
| Backups | The operator's schedule; the scripts never prune |

## Accounts and recovery

Users exist because the first owner was provisioned from environment variables at first start. There is no self-registration, invitation flow, password change or password-reset (no e-mail infrastructure exists). A lost owner password is recovered by an operator: stop the API, update the user's hash in PostgreSQL with a BCrypt hash produced elsewhere, and restart. Clear `spring_session_attributes` and `spring_session` as well if existing sessions must be ended. Additional people cannot be added through the UI in this release; the pilot is scoped to a single operator team that shares the owner account or whose accounts are inserted by the operator.

## Time and locale

Every stored timestamp is a UTC instant (`timestamptz`, Java `Instant`). Scheduling input is a local wall-clock time that the browser converts to UTC using the operator's own timezone, and the page states that timezone next to the schedule form; no server code depends on the server's timezone or locale. A wall-clock time skipped by a DST change is moved forward by the browser and an ambiguous one resolves to its first occurrence (covered by unit tests for Europe/Bucharest and America/New_York).
