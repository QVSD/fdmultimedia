# workers

Phase 4 includes a standalone Java worker agent in `java-agent/`. It registers
the current machine with the Spring control plane, sends periodic heartbeats,
polls for work, and executes only the safe `SYSTEM_TEST` job type.

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

Optional runtime tuning:

```bash
FDM_WORKER_NAME=dragos-laptop \
FDM_WORKER_HEARTBEAT_SECONDS=10 \
FDM_WORKER_JOB_POLL_SECONDS=3 \
FDM_WORKER_ID_FILE=.fdm-worker-id \
java -jar target/worker-agent-0.1.0-SNAPSHOT.jar
```

The agent loop is:

1. register or update this installation
2. heartbeat in a dedicated loop
3. poll `POST /api/worker-agent/jobs/claim`
4. start and execute a claimed `SYSTEM_TEST`
5. report completion or failure

When no jobs exist, polling backs off using `FDM_WORKER_JOB_POLL_SECONDS`.
Temporary server/network failures are logged and retried on the next poll.

`SYSTEM_TEST` accepts only a bounded message and duration. It never executes
shell commands, video processing, AI workloads, browser automation, publishing,
or arbitrary user-provided code. See [docs/ROADMAP.md](../docs/ROADMAP.md) and
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for later phases and the rule
that a worker is interchangeable compute, never tied 1:1 to a Robot.
