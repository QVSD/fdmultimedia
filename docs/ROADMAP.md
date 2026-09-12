# Roadmap

The repository is currently at Phase 6C. Completed phases are marked below;
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
   derivatives from READY + INSPECTED video assets. *(current phase)*
9. **Capabilities and processing prep** — broaden worker capability matching
   for future media operations.
10. **FFmpeg processing** — richer automated video processing pipelines.
11. **Scheduler / load balancing** — deciding which worker runs which job.
12. **Social account integrations** — connecting and publishing to external
   platforms.
13. **AI content** — AI-assisted content creation.
14. **Analytics / revenue** — performance analytics and revenue tracking.
