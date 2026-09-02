# Roadmap

Phase 1 (this repository, today) establishes the project foundation only.
The phases below are the planned high-level direction — none of them are
implemented yet, and this file intentionally does not go into
implementation detail for anything beyond the current phase.

1. **Foundation** — Angular + Spring Boot + PostgreSQL + RabbitMQ +
   Nginx + Docker Compose, modular monolith skeleton, health checks.
   *(this phase)*
2. **Users / workspaces** — accounts, workspaces/tenancy, authentication.
3. **Worker registration** — workers can register themselves with the
   control plane.
4. **Heartbeats / capabilities** — workers report liveness and what kinds
   of jobs they can run.
5. **Distributed jobs** — the control plane can enqueue jobs onto RabbitMQ
   and workers can claim and complete them.
6. **Video import** — bringing source video into the platform.
7. **FFmpeg processing** — automated video processing pipelines.
8. **Scheduler / load balancing** — deciding which worker runs which job.
9. **Social account integrations** — connecting and publishing to external
   platforms.
10. **AI content** — AI-assisted content creation.
11. **Analytics / revenue** — performance analytics and revenue tracking.
