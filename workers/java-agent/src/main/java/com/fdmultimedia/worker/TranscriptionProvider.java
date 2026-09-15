package com.fdmultimedia.worker;

import java.io.IOException;
import java.nio.file.Path;

interface TranscriptionProvider {

    boolean isAvailable();

    String providerName();

    String modelName();

    TranscriptionResult transcribe(Path audioFile, TranscriptionAuthorization authorization)
            throws IOException, InterruptedException, ImportFailureException;
}
