package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.publishing.Publication;
import org.springframework.stereotype.Component;

@Component
public class TestPublicationAnalyticsProvider implements PublicationAnalyticsProvider {
    @Override
    public SocialPlatform platform() {
        return SocialPlatform.TEST;
    }

    @Override
    public NormalizedAnalytics collect(Publication publication, int ageBucket) {
        if (ageBucket < 0 || ageBucket > 5) {
            throw new IllegalArgumentException("Invalid analytics age bucket");
        }
        long base = 100 + Math.floorMod(publication.getId().getMostSignificantBits(), 37);
        long[] multipliers = {1, 2, 5, 9, 15, 22};
        long views = base * multipliers[ageBucket];
        return new NormalizedAnalytics(views, views * 4 / 5, views / 10,
                views / 50, views / 25, views / 20, views / 10 + views / 50 + views / 25 + views / 20,
                null, null, null, "TEST_ANALYTICS_V1");
    }
}
