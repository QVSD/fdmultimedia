package com.fdmultimedia.worker;

import java.io.IOException;
import java.nio.file.Path;

interface PublishingProvider {

    boolean isAvailable();

    String platform();

    PublishResult publish(Path mediaFile, PublicationAuthorization authorization)
            throws IOException, InterruptedException, ImportFailureException;
}
