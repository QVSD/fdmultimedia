/**
 * Controlled, workspace-scoped content pools a Robot may select from
 * (Phase 11D) — never a URL scraper, never a Worker, never a Job, never a
 * Robot itself. A {@code ContentSource} is a small organizational object
 * ("this named pool of media") plus explicit {@code ContentSourceAsset}
 * membership rows linking it to existing workspace {@code MediaAsset}s; it
 * has no opinion about who selects from it or how. This package
 * deliberately does not depend on {@code com.fdmultimedia.api.robots} —
 * per-Robot selection policy and consumption tracking live there instead,
 * so a ContentSource stays reusable by anything that might read it later.
 */
package com.fdmultimedia.api.contentsources;
