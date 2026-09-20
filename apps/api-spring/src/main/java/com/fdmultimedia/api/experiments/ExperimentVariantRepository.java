package com.fdmultimedia.api.experiments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, UUID> {

    List<ExperimentVariant> findByExperimentOrderByVariantKeyAsc(Experiment experiment);

    Optional<ExperimentVariant> findByExperimentAndVariantKey(Experiment experiment, ExperimentVariantKey variantKey);
}
