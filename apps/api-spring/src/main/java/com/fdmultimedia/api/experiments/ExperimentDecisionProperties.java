package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.experiment-decision")
public class ExperimentDecisionProperties {
    private BigDecimal minAssignmentOutcomeCoverage = new BigDecimal("0.60");
    private BigDecimal attritionWarningPercentagePoints = new BigDecimal("20");
    private BigDecimal protocolDeviationWarningRate = new BigDecimal("0.20");

    public BigDecimal getMinAssignmentOutcomeCoverage() { return minAssignmentOutcomeCoverage; }
    public void setMinAssignmentOutcomeCoverage(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Coverage must be in (0,1]");
        minAssignmentOutcomeCoverage = value;
    }
    public BigDecimal getAttritionWarningPercentagePoints() { return attritionWarningPercentagePoints; }
    public void setAttritionWarningPercentagePoints(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("Attrition threshold must be in (0,100]");
        attritionWarningPercentagePoints = value;
    }
    public BigDecimal getProtocolDeviationWarningRate() { return protocolDeviationWarningRate; }
    public void setProtocolDeviationWarningRate(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Deviation rate must be in (0,1]");
        protocolDeviationWarningRate = value;
    }
}
