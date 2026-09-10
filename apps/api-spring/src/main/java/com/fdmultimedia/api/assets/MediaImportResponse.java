package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.jobs.JobSummary;

public record MediaImportResponse(MediaAssetSummary asset, JobSummary job) {
}
