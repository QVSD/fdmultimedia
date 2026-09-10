# Roadmap

The repository is currently at Phase 5. Completed phases are marked below;
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
   through presigned object-storage URLs, and mark assets READY. *(current
   phase)*
6. **Capabilities** — workers report what kinds of jobs they can run.
7. **FFmpeg processing** — automated video processing pipelines.
8. **Scheduler / load balancing** — deciding which worker runs which job.
9. **Social account integrations** — connecting and publishing to external
   platforms.
10. **AI content** — AI-assisted content creation.
11. **Analytics / revenue** — performance analytics and revenue tracking.
