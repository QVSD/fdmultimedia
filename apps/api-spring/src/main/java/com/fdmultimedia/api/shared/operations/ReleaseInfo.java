package com.fdmultimedia.api.shared.operations;

import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * The release identity of this API build: version and build time come from Maven's build-info, the commit from the image build
 * argument (never from the running environment's git state). Nothing here is secret; it is shown to operators only.
 */
@Component
public class ReleaseInfo {
    private final String version;
    private final String commit;
    private final Instant builtAt;

    public ReleaseInfo(ObjectProvider<BuildProperties> build, @Value("${app.release.commit:unknown}") String commit) {
        BuildProperties properties = build.getIfAvailable();
        this.version = properties == null ? "unknown" : properties.getVersion();
        this.builtAt = properties == null ? null : properties.getTime();
        this.commit = commit == null || commit.isBlank() ? "unknown" : commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    public String version() { return version; }
    public String commit() { return commit; }
    public Instant builtAt() { return builtAt; }
}
