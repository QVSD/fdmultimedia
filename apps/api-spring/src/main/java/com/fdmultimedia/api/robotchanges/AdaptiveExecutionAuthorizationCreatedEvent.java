package com.fdmultimedia.api.robotchanges;

import java.util.UUID;

/** Published after a human creates an authorization; the executor evaluates it once after commit. */
public record AdaptiveExecutionAuthorizationCreatedEvent(UUID authorizationId) {}
