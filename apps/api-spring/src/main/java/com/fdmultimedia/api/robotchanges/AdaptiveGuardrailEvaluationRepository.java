package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdaptiveGuardrailEvaluationRepository extends JpaRepository<AdaptiveGuardrailEvaluation,UUID>{
    Optional<AdaptiveGuardrailEvaluation> findTopByWorkspaceAndProposalIdOrderByEvaluatedAtDesc(Workspace workspace,UUID proposalId);
}
