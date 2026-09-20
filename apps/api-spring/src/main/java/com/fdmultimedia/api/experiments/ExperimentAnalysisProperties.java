package com.fdmultimedia.api.experiments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Mirrors {@code PerformanceInsightProperties}'s exact shape (item 29/30).
 * Default {@code 10} is the generally-recommended minimum for a Welch
 * t-test to behave reasonably; this repository deliberately ships
 * {@code 5} instead, matching {@code PerformanceInsightProperties}'s own
 * minimum-sample-size default, because the current local TEST dataset and
 * early product stage make a stricter default impractical for runtime
 * acceptance — this is a considered choice, not a shortcut, and is not
 * lowered any further for testing (see docs/DEVELOPMENT.md).
 */
@Component
@ConfigurationProperties(prefix = "app.experiment-analysis")
public class ExperimentAnalysisProperties {
    private int minSamplePerVariant = 5;

    public int getMinSamplePerVariant() {
        return minSamplePerVariant;
    }

    public void setMinSamplePerVariant(int minSamplePerVariant) {
        if (minSamplePerVariant < 1) {
            throw new IllegalArgumentException("Experiment analysis minimum sample per variant must be at least 1");
        }
        this.minSamplePerVariant = minSamplePerVariant;
    }
}
