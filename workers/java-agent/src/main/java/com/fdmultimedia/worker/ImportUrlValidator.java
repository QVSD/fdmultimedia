package com.fdmultimedia.worker;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

final class ImportUrlValidator implements SourceUrlPolicy {

    @Override
    public ValidatedSourceUrl validate(String rawUrl) throws ImportFailureException {
        URI uri;
        try {
            uri = new URI(rawUrl == null ? "" : rawUrl.trim());
        } catch (URISyntaxException ex) {
            throw new ImportFailureException("INVALID_URL", "Invalid source URL", true);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ImportFailureException("INVALID_URL_SCHEME", "Only http and https URLs are allowed", true);
        }
        if (uri.getUserInfo() != null) {
            throw new ImportFailureException("URL_CREDENTIALS_REJECTED", "URLs with embedded credentials are not allowed", true);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ImportFailureException("INVALID_URL_HOST", "URL host is required", true);
        }
        return new ValidatedSourceUrl(uri, validateHost(host));
    }

    private InetAddress validateHost(String rawHost) throws ImportFailureException {
        String host = IDN.toASCII(rawHost).toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("metadata.google.internal")) {
            throw new ImportFailureException("SSRF_BLOCKED", "URL host is not allowed", true);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new ImportFailureException("SOURCE_DNS_FAILED", "Source host could not be resolved", false);
        }
        for (InetAddress address : addresses) {
            if (blocked(address)) {
                throw new ImportFailureException("SSRF_BLOCKED", "URL resolves to a forbidden address", true);
            }
        }
        return addresses[0];
    }

    private boolean blocked(InetAddress address) {
        byte[] embeddedIpv4 = embeddedIpv4(address);
        if (embeddedIpv4 != null) {
            return cloudMetadata(embeddedIpv4) || reservedIpv4(embeddedIpv4);
        }
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || cloudMetadata(address)
                || reservedIpv4(address)
                || uniqueLocalIpv6(address);
    }

    private boolean cloudMetadata(InetAddress address) {
        return cloudMetadata(address.getAddress());
    }

    private boolean cloudMetadata(byte[] bytes) {
        return bytes.length == 4 && unsigned(bytes[0]) == 169 && unsigned(bytes[1]) == 254
                && unsigned(bytes[2]) == 169 && unsigned(bytes[3]) == 254;
    }

    private boolean reservedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        return reservedIpv4(address.getAddress());
    }

    private boolean reservedIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || first >= 224;
    }

    private boolean uniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address && (unsigned(address.getAddress()[0]) & 0xfe) == 0xfc;
    }

    private byte[] embeddedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (!(address instanceof Inet6Address) || bytes.length != 16) {
            return null;
        }
        boolean leadingZeros = true;
        for (int index = 0; index < 10; index++) {
            leadingZeros = leadingZeros && bytes[index] == 0;
        }
        boolean compatible = bytes[10] == 0 && bytes[11] == 0;
        boolean mapped = bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        return leadingZeros && (compatible || mapped) ? Arrays.copyOfRange(bytes, 12, 16) : null;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }
}
