package com.fdmultimedia.worker;

import java.net.URI;

interface SourceUrlPolicy {

    ValidatedSourceUrl validate(String rawUrl) throws ImportFailureException;
}
