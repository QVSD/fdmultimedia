package com.fdmultimedia.worker;

import java.net.URI;

interface SourceUrlPolicy {

    URI validate(String rawUrl) throws ImportFailureException;
}
