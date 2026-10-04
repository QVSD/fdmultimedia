package com.fdmultimedia.api.robotchanges;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdaptiveExecutionAttemptRepository extends JpaRepository<AdaptiveExecutionAttempt, UUID> {
    Optional<AdaptiveExecutionAttempt> findTopByAuthorizationIdOrderByAttemptedAtDesc(UUID authorizationId);

    List<AdaptiveExecutionAttempt> findByAuthorizationIdOrderByAttemptedAtDesc(UUID authorizationId, Pageable pageable);
}
