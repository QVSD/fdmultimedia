package com.fdmultimedia.api.assets;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class UrlSecurityValidator {

    public URI validateHttpUrl(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl == null ? "" : rawUrl.trim());
        } catch (URISyntaxException ex) {
            throw badRequest("Invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw badRequest("Only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw badRequest("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw badRequest("URL host is required");
        }
        validateHost(host);
        return uri;
    }

    public void validateRedirectTarget(URI uri) {
        validateHttpUrl(uri.toString());
    }

    private void validateHost(String rawHost) {
        String host = IDN.toASCII(rawHost).toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw badRequest("Localhost URLs are not allowed");
        }
        if (host.equals("metadata.google.internal")) {
            throw badRequest("Cloud metadata URLs are not allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw badRequest("URL host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw badRequest("URL resolves to a forbidden address");
            }
        }
    }

    private boolean isBlocked(InetAddress address) {
        byte[] embeddedIpv4 = embeddedIpv4(address);
        if (embeddedIpv4 != null) {
            return isCloudMetadata(embeddedIpv4) || isReservedIpv4(embeddedIpv4);
        }
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCloudMetadata(address)
                || isReservedIpv4(address)
                || isUniqueLocalIpv6(address);
    }

    private boolean isCloudMetadata(InetAddress address) {
        return isCloudMetadata(address.getAddress());
    }

    private boolean isCloudMetadata(byte[] bytes) {
        return bytes.length == 4
                && unsigned(bytes[0]) == 169
                && unsigned(bytes[1]) == 254
                && unsigned(bytes[2]) == 169
                && unsigned(bytes[3]) == 254;
    }

    private boolean isReservedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        return isReservedIpv4(address.getAddress());
    }

    private boolean isReservedIpv4(byte[] bytes) {
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

    private boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        int first = unsigned(address.getAddress()[0]);
        return (first & 0xfe) == 0xfc;
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

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
