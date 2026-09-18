/**
 * Content drafts: the product-facing bridge between a source {@code MediaAsset}
 * (or a {@code HighlightCandidate}) and a published {@code Publication}.
 *
 * Phase 11A: a {@code ContentDraft} is not a Job, Publication, MediaAsset, or
 * Robot. It orchestrates the existing {@code CREATE_CLIP} /
 * {@code CREATE_SOCIAL_VERTICAL} Jobs (via {@code MediaAssetService}, never
 * FFmpeg directly) and the existing {@code PublishingService} — it does not
 * duplicate either. Workflow progress is durable state on the draft row
 * itself (no in-memory callbacks), reconciled idempotently whenever a draft
 * is read.
 */
package com.fdmultimedia.api.contentdrafts;
