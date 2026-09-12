package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.jobs.JobSummary;

public record CreateClipResponse(MediaAssetSummary asset, JobSummary job) {
}
