package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SchedulerCadencePolicyTest {
    @Test
    void defaultsMatchTheSchedulersOwnDefaults() {
        SchedulerCadencePolicy policy = new SchedulerCadencePolicy(new MockEnvironment());
        assertThat(policy.cadence(SchedulerOperationTracker.PUBLISH_SCHEDULE)).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.cadence(SchedulerOperationTracker.ADAPTIVE_EXECUTION)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.cadence(SchedulerOperationTracker.ADAPTIVE_MEMORY)).isEqualTo(Duration.ofHours(1));
        assertThat(policy.cadence(SchedulerOperationTracker.SCHEDULING_RETENTION)).isEqualTo(Duration.ofDays(1));
        assertThat(policy.cadence(SchedulerOperationTracker.OPERATIONS_INCIDENTS)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void configuredIntervalsAndEnabledFlagsAreHonoured() {
        MockEnvironment env = new MockEnvironment().withProperty("app.robots.poll-interval-ms", "5000")
                .withProperty("app.robots.automation-enabled", "false");
        SchedulerCadencePolicy policy = new SchedulerCadencePolicy(env);
        assertThat(policy.cadence(SchedulerOperationTracker.ROBOT_AUTOMATION)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.enabled(SchedulerOperationTracker.ROBOT_AUTOMATION)).isFalse();
        assertThat(policy.enabled(SchedulerOperationTracker.PUBLISH_SCHEDULE)).isTrue();
    }

    @Test
    void onDemandAndUnknownOperationsHaveNoExpectedTick() {
        SchedulerCadencePolicy policy = new SchedulerCadencePolicy(new MockEnvironment());
        assertThat(policy.cadence("job-lease-recovery")).isEqualTo(Duration.ZERO);
        assertThat(policy.cadence("spring-session-cleanup")).isEqualTo(Duration.ZERO);
        assertThat(policy.cadence("unknown")).isEqualTo(Duration.ZERO);
    }
}
