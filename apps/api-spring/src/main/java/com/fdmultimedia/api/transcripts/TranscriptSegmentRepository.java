package com.fdmultimedia.api.transcripts;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, java.util.UUID> {

    List<TranscriptSegment> findByTranscriptOrderBySequenceAsc(MediaTranscript transcript);

    void deleteByTranscript(MediaTranscript transcript);
}
