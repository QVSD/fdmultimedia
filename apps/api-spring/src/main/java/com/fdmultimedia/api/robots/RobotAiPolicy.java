package com.fdmultimedia.api.robots;

/**
 * Independent of {@link RobotAutonomyMode} — this axis decides whether a
 * Robot invokes AI content enrichment at all, the other decides what
 * happens with a READY Draft afterward. Never merge the two into a single
 * "how autonomous" switch.
 *
 * <p>NO_AI: exact Phase 11D/12B behavior — the Robot never creates a
 * {@code ContentSuggestion}.
 * <p>GENERATE_FOR_REVIEW: the Robot creates exactly one AI suggestion and
 * waits for a human to Apply or Discard it before continuing.
 * <p>GENERATE_AND_APPLY: the Robot creates exactly one AI suggestion and,
 * once it is READY, applies it itself through the same Apply path a human
 * uses, then continues.
 */
public enum RobotAiPolicy {
    NO_AI,
    GENERATE_FOR_REVIEW,
    GENERATE_AND_APPLY
}
