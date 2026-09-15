package com.fdmultimedia.api.transcripts;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaTranscriptRepository extends JpaRepository<MediaTranscript, UUID> {

    List<MediaTranscript> findByWorkspaceAndAssetOrderByCreatedAtDesc(Workspace workspace, MediaAsset asset);

    Optional<MediaTranscript> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<MediaTranscript> findByTranscriptionJobId(UUID transcriptionJobId);

    Optional<MediaTranscript> findFirstByWorkspaceAndAssetAndProviderAndModelAndStatusInOrderByCreatedAtDesc(
            Workspace workspace,
            MediaAsset asset,
            String provider,
            String model,
            List<TranscriptStatus> statuses);
}
