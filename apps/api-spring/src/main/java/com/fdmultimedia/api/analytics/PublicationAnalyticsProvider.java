package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.publishing.Publication;

public interface PublicationAnalyticsProvider {
    SocialPlatform platform();
    NormalizedAnalytics collect(Publication publication, int ageBucket);
}
