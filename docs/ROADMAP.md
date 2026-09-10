# Roadmap

The repository is currently at Phase 4. Completed phases are marked below;
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
   durable results with leases and bounded retries. *(current phase)*
5. **Capabilities** — workers report what kinds of jobs they can run.
6. **Video import** — bringing source video into the platform.
7. **FFmpeg processing** — automated video processing pipelines.
8. **Scheduler / load balancing** — deciding which worker runs which job.
9. **Social account integrations** — connecting and publishing to external
   platforms.
10. **AI content** — AI-assisted content creation.
11. **Analytics / revenue** — performance analytics and revenue tracking.
