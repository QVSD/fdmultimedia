package com.fdmultimedia.api.analytics;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds for Phase 13C's deterministic insight engine. Kept deliberately
 * separate from {@link PublicationAnalyticsProperties} (collection) and the
 * dashboard's own request bounds (date range/group limits) — these three
 * govern independent concerns and none of the failure modes of one should
 * silently change the behavior of another.
 */
@Component
@ConfigurationProperties(prefix = "app.performance-insights")
public class PerformanceInsightProperties {
    private int minSampleSize = 5;
    private BigDecimal minCoverage = new BigDecimal("0.60");
    private BigDecimal materialDifferencePercent = BigDecimal.TEN;

    public int getMinSampleSize() { return minSampleSize; }
    public void setMinSampleSize(int minSampleSize) {
        if (minSampleSize < 1) {
            throw new IllegalArgumentException("Insight minimum sample size must be at least 1");
        }
        this.minSampleSize = minSampleSize;
    }

    public BigDecimal getMinCoverage() { return minCoverage; }
    public void setMinCoverage(BigDecimal minCoverage) {
        if (minCoverage == null || minCoverage.signum() <= 0 || minCoverage.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Insight minimum coverage must be greater than 0 and at most 1");
        }
        this.minCoverage = minCoverage;
    }

    public BigDecimal getMaterialDifferencePercent() { return materialDifferencePercent; }
    public void setMaterialDifferencePercent(BigDecimal materialDifferencePercent) {
        if (materialDifferencePercent == null || materialDifferencePercent.signum() < 0) {
            throw new IllegalArgumentException("Insight material-difference threshold must be zero or greater");
        }
        this.materialDifferencePercent = materialDifferencePercent;
    }
}
