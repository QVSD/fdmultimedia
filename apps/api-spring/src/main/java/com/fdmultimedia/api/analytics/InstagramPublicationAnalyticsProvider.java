package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.publishing.Publication;
import org.springframework.stereotype.Component;

/** Existing Instagram Login grants only basic and content_publish, not insights. */
@Component
public class InstagramPublicationAnalyticsProvider implements PublicationAnalyticsProvider {
    @Override
    public SocialPlatform platform() {
        return SocialPlatform.INSTAGRAM;
    }

    @Override
    public NormalizedAnalytics collect(Publication publication, int ageBucket) {
        throw new AnalyticsCollectionException("ANALYTICS_PERMISSION_DENIED",
                "Instagram analytics requires a separate insights permission and account re-authorization", false);
    }
}
