package com.fdmultimedia.api.experiments;

/**
 * DETERMINISTIC_BALANCED_V1: approximately 50/50 A/B allocation via
 * deterministic least-assigned selection under a DB row lock (see
 * {@link ExperimentAssignmentService}) — never a random draw, never an
 * in-memory counter, never dependent on any performance outcome.
 */
public enum AssignmentStrategy {
    DETERMINISTIC_BALANCED_V1
}
