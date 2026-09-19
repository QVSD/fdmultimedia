package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.workers.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobExecutionMetricService {

    private final JobExecutionMetricRepository metrics;
    private final MediaAssetRepository assets;

    public JobExecutionMetricService(JobExecutionMetricRepository metrics, MediaAssetRepository assets) {
        this.metrics = metrics;
        this.assets = assets;
    }

    public void record(Job job, Worker worker, JobExecutionOutcome outcome, Instant finishedAt) {
        record(job, new JobExecutionSnapshot(worker, job.getAttemptCount(), job.getQueuedAt(), job.getAssignedAt(), job.getStartedAt()), outcome, finishedAt);
    }

    public void record(Job job, JobExecutionSnapshot snapshot, JobExecutionOutcome outcome, Instant finishedAt) {
        if (snapshot.attempt() <= 0 || metrics.findByJobIdAndAttempt(job.getId(), snapshot.attempt()).isPresent()) {
            return;
        }
        metrics.save(new JobExecutionMetric(
                job,
                snapshot.worker(),
                snapshot.attempt(),
                outcome,
                millisBetween(snapshot.queuedAt(), snapshot.assignedAt()),
                millisBetween(snapshot.startedAt(), finishedAt),
                millisBetween(snapshot.queuedAt(), finishedAt),
                workloadHints(job),
                finishedAt));
    }

    private Long millisBetween(Instant start, Instant end) {
        if (start == null || end == null || end.isBefore(start)) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }

    private WorkloadHints workloadHints(Job job) {
        Map<String, Object> payload = job.getPayload();
        return switch (job.getType()) {
            case SYSTEM_TEST -> new WorkloadHints(null, longPayload(payload, "durationMs"), null, null, null, null, null);
            case IMPORT_MEDIA, INSPECT_MEDIA -> assetHints(uuidPayload(payload, "assetId"), null, null, null);
            case CREATE_CLIP -> clipHints(payload);
            case CREATE_SOCIAL_VERTICAL -> assetHints(uuidPayload(payload, "sourceAssetId"), null, null, null);
            case TRANSCRIBE_MEDIA -> assetHints(
                    uuidPayload(payload, "assetId"),
                    stringPayload(payload, "provider"),
                    stringPayload(payload, "model"),
                    null);
            case ANALYZE_HIGHLIGHTS -> assetHints(
                    uuidPayload(payload, "assetId"),
                    null,
                    null,
                    stringPayload(payload, "analyzerType"));
            case PUBLISH_MEDIA -> assetHints(uuidPayload(payload, "assetId"), null, null, null);
            // No media-asset-shaped hints apply — the payload only carries a
            // ContentDraft reference for observability, not size/duration.
            case GENERATE_SOCIAL_COPY -> new WorkloadHints(null, null, null, null, null, null, null);
        };
    }

    private WorkloadHints clipHints(Map<String, Object> payload) {
        WorkloadHints assetHints = assetHints(uuidPayload(payload, "sourceAssetId"), null, null, null);
        Long clipDuration = longPayload(payload, "durationMs");
        return new WorkloadHints(
                assetHints.sizeBytes(),
                clipDuration != null ? clipDuration : assetHints.durationMs(),
                assetHints.width(),
                assetHints.height(),
                null,
                null,
                null);
    }

    private WorkloadHints assetHints(UUID assetId, String provider, String model, String analyzerType) {
        if (assetId == null) {
            return new WorkloadHints(null, null, null, null, provider, model, analyzerType);
        }
        Optional<MediaAsset> asset = assets.findById(assetId);
        if (asset.isEmpty()) {
            return new WorkloadHints(null, null, null, null, provider, model, analyzerType);
        }
        MediaAsset mediaAsset = asset.get();
        return new WorkloadHints(
                mediaAsset.getFileSizeBytes(),
                mediaAsset.getDurationMs(),
                mediaAsset.getWidth(),
                mediaAsset.getHeight(),
                provider,
                model,
                analyzerType);
    }

    private UUID uuidPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Long longPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }
}
