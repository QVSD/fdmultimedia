package com.fdmultimedia.api.shared.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class ObjectStorageHealthIndicatorTest {
    @Test
    void reportsComponentDownWithoutAffectingProcessLivenessContract() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.isAvailable()).thenReturn(false);

        assertThat(new ObjectStorageHealthIndicator(storage).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
