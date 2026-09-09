# workers

Phase 3 includes a standalone Java worker agent in `java-agent/`. It registers
the current machine with the Spring control plane and sends periodic
heartbeats so the Compute page can show online/offline worker status.

The agent persists a random installation UUID locally and uses that as the
machine identifier. It does not use MAC addresses or other hardware IDs.

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

The agent only supports registration and heartbeat in this phase. It does not
pull jobs, process video, run AI workloads, or bind workers to Robots. See
[docs/ROADMAP.md](../docs/ROADMAP.md) and
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for the later phases and the
rule that a worker is interchangeable compute, never tied 1:1 to a Robot.
