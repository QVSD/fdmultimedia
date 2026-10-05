package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.opscontrol.OpsModels.ComponentStatus;
import com.fdmultimedia.api.opscontrol.OpsModels.Dependency;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Bounded, concurrent, short-cached dependency probing. Every probe is run on its own thread and awaited together against one
 * strict deadline, so N slow dependencies cost one timeout, not N. A probe that exceeds the deadline is reported UNAVAILABLE
 * ({@code TIMEOUT}) and that verdict is cached for the TTL; at most one probe per dependency is ever in flight, so a hung
 * dependency can neither pile up threads nor make repeated page refreshes slow.
 */
@Service
public class DependencyHealthService {
    public record ProbeResult(ComponentStatus status, String detail) {}

    public interface Probe {
        String name();
        boolean configured();
        /** May block; the service bounds it. */
        ProbeResult probe();
    }

    private record Entry(ProbeResult result, Instant observedAt, long probeMillis) {}

    private final List<Probe> probes;
    private final OpsProperties properties;
    private final Clock clock;
    private final ExecutorService executor;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

    public DependencyHealthService(List<Probe> probes, OpsProperties properties, Clock clock) {
        this.probes = List.copyOf(probes);
        this.properties = properties;
        this.clock = clock;
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(2, Math.min(8, probes.size() + 1)), r -> {
            Thread t = new Thread(r, "ops-probe-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public List<Dependency> snapshot() {
        Instant now = Instant.now(clock);
        Duration ttl = properties.getProbeCacheTtl();
        Duration timeout = properties.getProbeTimeout();
        Map<String, CompletableFuture<Entry>> waiting = new ConcurrentHashMap<>();
        for (Probe probe : probes) {
            if (!probe.configured()) continue;
            CompletableFuture<Entry> started = startIfStale(probe, now, ttl);
            if (started != null) waiting.put(probe.name(), started);
        }
        if (!waiting.isEmpty()) {
            try {
                CompletableFuture.allOf(waiting.values().toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException expected) {
                // slow probes are handled below: they get a cached TIMEOUT verdict
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException ignored) {
                // each future never completes exceptionally (see start)
            }
            Instant after = Instant.now(clock);
            waiting.forEach((name, future) -> {
                markTimeoutIfStillRunning(name, future, after, timeout);
            });
        }
        Instant observedNow = Instant.now(clock);
        List<Dependency> result = new ArrayList<>();
        for (Probe probe : probes) {
            if (!probe.configured()) {
                result.add(new Dependency(probe.name(), ComponentStatus.UNKNOWN, "NOT_CONFIGURED", false, null, 0, false, null));
                continue;
            }
            Entry entry = cache.get(probe.name());
            if (entry == null) {
                result.add(new Dependency(probe.name(), ComponentStatus.UNKNOWN, "NOT_PROBED_YET", true, null, 0, false, null));
                continue;
            }
            boolean fromCache = !waiting.containsKey(probe.name());
            result.add(new Dependency(probe.name(), entry.result().status(), entry.result().detail(), true, entry.observedAt(),
                    Math.max(0, Duration.between(entry.observedAt(), observedNow).toSeconds()), fromCache, entry.probeMillis()));
        }
        return result;
    }

    /** Check-then-act is atomic: a fresh cache entry or an in-flight probe is reused, otherwise exactly one new probe starts. */
    private synchronized CompletableFuture<Entry> startIfStale(Probe probe, Instant now, Duration ttl) {
        String name = probe.name();
        Entry cached = cache.get(name);
        if (cached != null && Duration.between(cached.observedAt(), now).compareTo(ttl) < 0) return null;
        CompletableFuture<Entry> running = inflight.get(name);
        if (running != null) return running;
        CompletableFuture<Entry> future = CompletableFuture.supplyAsync(() -> {
            long began = System.nanoTime();
            ProbeResult outcome;
            try {
                outcome = probe.probe();
            } catch (RuntimeException ex) {
                outcome = new ProbeResult(ComponentStatus.UNAVAILABLE, "ERROR");
            }
            Entry entry = new Entry(outcome, Instant.now(clock), (System.nanoTime() - began) / 1_000_000);
            complete(name, entry);
            return entry;
        }, executor);
        inflight.put(name, future);
        return future;
    }

    private synchronized void markTimeoutIfStillRunning(String name, CompletableFuture<Entry> future, Instant at, Duration timeout) {
        if (!future.isDone()) cache.put(name, new Entry(new ProbeResult(ComponentStatus.UNAVAILABLE, "TIMEOUT"), at, timeout.toMillis()));
    }

    private synchronized void complete(String name, Entry entry) {
        cache.put(name, entry);
        inflight.remove(name);
    }

    /** For tests and shutdown. */
    public void shutdown() { executor.shutdownNow(); }
}
