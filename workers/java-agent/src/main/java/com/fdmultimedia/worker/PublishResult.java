package com.fdmultimedia.worker;

import java.time.Instant;

record PublishResult(String providerRequestId, String providerPublicationId, Instant publishedAt) {
}
