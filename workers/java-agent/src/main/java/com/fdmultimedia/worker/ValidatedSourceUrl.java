package com.fdmultimedia.worker;

import java.net.InetAddress;
import java.net.URI;

record ValidatedSourceUrl(URI uri, InetAddress address) {
}
