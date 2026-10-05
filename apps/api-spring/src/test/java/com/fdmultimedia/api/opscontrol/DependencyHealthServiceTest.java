package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.opscontrol.DependencyHealthService.Probe;
import com.fdmultimedia.api.opscontrol.DependencyHealthService.ProbeResult;
import com.fdmultimedia.api.opscontrol.OpsModels.ComponentStatus;
import com.fdmultimedia.api.opscontrol.OpsModels.Dependency;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DependencyHealthServiceTest {
    private final OpsProperties properties = new OpsProperties();
    private DependencyHealthService service;

    @AfterEach
    void tearDown() { if (service != null) service.shutdown(); }

    private static final class StubProbe implements Probe {
        final String name; final boolean configured; final long sleepMillis; final ComponentStatus status; final AtomicInteger calls = new AtomicInteger();
        final boolean fail; volatile CountDownLatch release;
        StubProbe(String name, boolean configured, long sleepMillis, ComponentStatus status, boolean fail) {
            this.name = name; this.configured = configured; this.sleepMillis = sleepMillis; this.status = status; this.fail = fail;
        }
        public String name() { return name; }
        public boolean configured() { return configured; }
        public ProbeResult probe() {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("boom");
            try {
                if (release != null) release.await();
                else if (sleepMillis > 0) Thread.sleep(sleepMillis);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new ProbeResult(status, status == ComponentStatus.HEALTHY ? "UP" : "DOWN");
        }
    }

    private DependencyHealthService create(StubProbe... probes) {
        properties.setProbeTimeout(Duration.ofMillis(300));
        properties.setProbeCacheTtl(Duration.ofSeconds(10));
        service = new DependencyHealthService(List.of(probes), properties, Clock.systemUTC());
        return service;
    }

    @Test
    void healthyProbeIsReportedAndCached() {
        StubProbe probe = new StubProbe("POSTGRES", true, 0, ComponentStatus.HEALTHY, false);
        create(probe);
        Dependency first = service.snapshot().get(0);
        Dependency second = service.snapshot().get(0);
        assertThat(first.status()).isEqualTo(ComponentStatus.HEALTHY);
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(probe.calls.get()).isEqualTo(1);
    }

    @Test
    void slowProbeIsBoundedAndReportedUnavailableAsTimeout() {
        StubProbe slow = new StubProbe("MINIO", true, 5_000, ComponentStatus.HEALTHY, false);
        create(slow);
        long began = System.nanoTime();
        Dependency d = service.snapshot().get(0);
        long millis = (System.nanoTime() - began) / 1_000_000;
        assertThat(millis).isLessThan(1_000);
        assertThat(d.status()).isEqualTo(ComponentStatus.UNAVAILABLE);
        assertThat(d.detail()).isEqualTo("TIMEOUT");
    }

    @Test
    void timeoutVerdictIsCachedSoRepeatedRefreshesAreInstantAndDoNotPileUpProbes() {
        StubProbe slow = new StubProbe("MINIO", true, 5_000, ComponentStatus.HEALTHY, false);
        create(slow);
        service.snapshot();
        long began = System.nanoTime();
        for (int i = 0; i < 20; i++) assertThat(service.snapshot().get(0).detail()).isEqualTo("TIMEOUT");
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(250);
        assertThat(slow.calls.get()).isEqualTo(1);
    }

    @Test
    void severalSlowProbesCostOneTimeoutNotTheirSum() {
        StubProbe a = new StubProbe("A", true, 5_000, ComponentStatus.HEALTHY, false);
        StubProbe b = new StubProbe("B", true, 5_000, ComponentStatus.HEALTHY, false);
        StubProbe c = new StubProbe("C", true, 5_000, ComponentStatus.HEALTHY, false);
        create(a, b, c);
        long began = System.nanoTime();
        List<Dependency> result = service.snapshot();
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(900);
        assertThat(result).allSatisfy(d -> assertThat(d.status()).isEqualTo(ComponentStatus.UNAVAILABLE));
    }

    @Test
    void aHealthyProbeIsNotDelayedByASlowOne() {
        StubProbe slow = new StubProbe("MINIO", true, 5_000, ComponentStatus.HEALTHY, false);
        StubProbe fast = new StubProbe("POSTGRES", true, 0, ComponentStatus.HEALTHY, false);
        create(slow, fast);
        List<Dependency> result = service.snapshot();
        assertThat(result.stream().filter(d -> d.name().equals("POSTGRES")).findFirst().orElseThrow().status()).isEqualTo(ComponentStatus.HEALTHY);
        assertThat(result.stream().filter(d -> d.name().equals("MINIO")).findFirst().orElseThrow().status()).isEqualTo(ComponentStatus.UNAVAILABLE);
    }

    @Test
    void probeExceptionBecomesUnavailableWithoutLeakingTheMessage() {
        create(new StubProbe("RABBITMQ", true, 0, ComponentStatus.HEALTHY, true));
        Dependency d = service.snapshot().get(0);
        assertThat(d.status()).isEqualTo(ComponentStatus.UNAVAILABLE);
        assertThat(d.detail()).isEqualTo("ERROR").doesNotContain("boom");
    }

    @Test
    void unconfiguredDependencyIsUnknownNotUnavailableAndIsNeverProbed() {
        StubProbe probe = new StubProbe("OPTIONAL", false, 0, ComponentStatus.HEALTHY, false);
        create(probe);
        Dependency d = service.snapshot().get(0);
        assertThat(d.status()).isEqualTo(ComponentStatus.UNKNOWN);
        assertThat(d.configured()).isFalse();
        assertThat(d.detail()).isEqualTo("NOT_CONFIGURED");
        assertThat(probe.calls.get()).isZero();
    }

    @Test
    void anExpiredEntryTriggersExactlyOneNewProbeEvenUnderConcurrentSnapshots() throws Exception {
        StubProbe probe = new StubProbe("POSTGRES", true, 80, ComponentStatus.HEALTHY, false);
        properties.setProbeTimeout(Duration.ofMillis(500));
        properties.setProbeCacheTtl(Duration.ofSeconds(1));
        service = new DependencyHealthService(List.of(probe), properties, Clock.systemUTC());
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 8; i++) { Thread t = new Thread(service::snapshot); threads.add(t); t.start(); }
        for (Thread t : threads) t.join();
        assertThat(probe.calls.get()).isEqualTo(1);
    }

    @Test
    void recoveryIsObservedOnceTheCachedVerdictExpires() throws Exception {
        properties.setProbeTimeout(Duration.ofMillis(300));
        properties.setProbeCacheTtl(Duration.ofSeconds(1));
        var status = new java.util.concurrent.atomic.AtomicReference<>(ComponentStatus.UNAVAILABLE);
        Probe flapping = new Probe() {
            public String name() { return "MINIO"; }
            public boolean configured() { return true; }
            public ProbeResult probe() { return new ProbeResult(status.get(), status.get() == ComponentStatus.HEALTHY ? "UP" : "UNREACHABLE"); }
        };
        service = new DependencyHealthService(List.of(flapping), properties, Clock.systemUTC());
        assertThat(service.snapshot().get(0).status()).isEqualTo(ComponentStatus.UNAVAILABLE);
        status.set(ComponentStatus.HEALTHY);
        assertThat(service.snapshot().get(0).status()).isEqualTo(ComponentStatus.UNAVAILABLE);
        Thread.sleep(1_100);
        assertThat(service.snapshot().get(0).status()).isEqualTo(ComponentStatus.HEALTHY);
    }
}
