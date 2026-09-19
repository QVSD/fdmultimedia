package com.fdmultimedia.api.contentsuggestions;

import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentSuggestionRepository extends JpaRepository<ContentSuggestion, UUID> {

    List<ContentSuggestion> findByContentDraftOrderByCreatedAtDesc(ContentDraft contentDraft);

    Optional<ContentSuggestion> findByWorkspaceAndId(Workspace workspace, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContentSuggestion s where s.workspace = :workspace and s.id = :id")
    Optional<ContentSuggestion> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);

    Optional<ContentSuggestion> findByGenerationJob(Job generationJob);
}
