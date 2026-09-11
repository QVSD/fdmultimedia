package com.fdmultimedia.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class ImportMediaExecutor {

    private final SourceUrlPolicy urlValidator;

    ImportMediaExecutor() {
        this(new ImportUrlValidator());
    }

    ImportMediaExecutor(SourceUrlPolicy urlValidator) {
        this.urlValidator = urlValidator;
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        ImportMediaAuthorization authorization = client.authorizeImport(job.jobId(), machineIdentifier);
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("fdm-import-", ".media");
            DownloadedMedia media = download(authorization, tempFile);
            client.upload(URI.create(authorization.uploadUrl()), media.path(), media.contentType());
            client.completeImport(job.jobId(), machineIdentifier, authorization, media);
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    DownloadedMedia download(ImportMediaAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        ValidatedSourceUrl source = urlValidator.validate(authorization.sourceUrl());
        for (int redirect = 0; redirect <= authorization.maxRedirects(); redirect++) {
            DownloadResponse response = sendGet(source, authorization);
            int status = response.status();
            if (status >= 300 && status < 400) {
                Optional<String> location = response.firstHeader("location");
                response.close();
                if (location.isEmpty() || redirect == authorization.maxRedirects()) {
                    throw new ImportFailureException("TOO_MANY_REDIRECTS", "Source URL redirected too many times", true);
                }
                source = urlValidator.validate(source.uri().resolve(location.get()).toString());
                continue;
            }
            if (status >= 500) {
                response.close();
                throw new ImportFailureException("SOURCE_TEMPORARY_FAILURE", "Source server returned HTTP " + status, false);
            }
            if (status < 200 || status >= 300) {
                response.close();
                throw new ImportFailureException("SOURCE_REJECTED", "Source server returned HTTP " + status, true);
            }
            long contentLength = response.firstHeader("content-length").map(Long::parseLong).orElse(-1L);
            if (contentLength > authorization.maxDownloadSizeBytes()) {
                response.close();
                throw new ImportFailureException("MEDIA_TOO_LARGE", "Source media exceeds maximum size", true);
            }
            String contentType = response.firstHeader("content-type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("application/octet-stream");
            validateMediaContentType(contentType);
            String checksum = streamToFile(response.body(), target, authorization.maxDownloadSizeBytes());
            long size = Files.size(target);
            return new DownloadedMedia(
                    target,
                    originalFilename(source.uri()),
                    contentType,
                    size,
                    checksum,
                    containerFormat(contentType));
        }
        throw new ImportFailureException("TOO_MANY_REDIRECTS", "Source URL redirected too many times", true);
    }

    private DownloadResponse sendGet(ValidatedSourceUrl source, ImportMediaAuthorization authorization)
            throws IOException {
        URI uri = source.uri();
        int port = port(uri);
        int connectTimeoutMillis = Math.toIntExact(Duration.ofSeconds(authorization.connectTimeoutSeconds()).toMillis());
        int readTimeoutMillis = Math.toIntExact(Duration.ofSeconds(authorization.readTimeoutSeconds()).toMillis());
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(source.address(), port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            Socket transport = socket;
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) sslSocketFactory
                        .createSocket(socket, uri.getHost(), port, true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (isDnsHostname(uri.getHost())) {
                    parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                }
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                transport = ssl;
            }

            OutputStream output = transport.getOutputStream();
            output.write(requestBytes(uri));
            output.flush();

            InputStream input = transport.getInputStream();
            String statusLine = readAsciiLine(input);
            if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                throw new IOException("Source returned an invalid HTTP response");
            }
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length < 2) {
                throw new IOException("Source returned an invalid HTTP status");
            }
            int status = Integer.parseInt(statusParts[1]);
            Map<String, List<String>> headers = readHeaders(input);
            InputStream body = bodyStream(input, headers, transport);
            return new DownloadResponse(status, headers, body);
        } catch (IOException | RuntimeException ex) {
            socket.close();
            throw ex;
        }
    }

    private byte[] requestBytes(URI uri) {
        String requestTarget = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            requestTarget += "?" + uri.getRawQuery();
        }
        String request = "GET " + requestTarget + " HTTP/1.1\r\n"
                + "Host: " + hostHeader(uri) + "\r\n"
                + "User-Agent: fdm-worker/0.1.0\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        return request.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private String hostHeader(URI uri) {
        String host = uri.getHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = uri.getPort();
        if (port < 0 || port == defaultPort(uri)) {
            return host;
        }
        return host + ":" + port;
    }

    private int port(URI uri) {
        return uri.getPort() > 0 ? uri.getPort() : defaultPort(uri);
    }

    private int defaultPort(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isDnsHostname(String host) {
        return !host.contains(":") && !host.matches("[0-9.]+");
    }

    private Map<String, List<String>> readHeaders(InputStream input) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String line;
        while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        return headers;
    }

    private InputStream bodyStream(InputStream input, Map<String, List<String>> headers, Socket socket) {
        InputStream body = first(headers, "transfer-encoding")
                .filter(value -> value.toLowerCase(Locale.ROOT).contains("chunked"))
                .map(ignored -> (InputStream) new ChunkedInputStream(input))
                .orElse(input);
        return new ClosingInputStream(body, socket);
    }

    private Optional<String> first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private String readAsciiLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                line.setLength(line.length() - 1);
                return line.toString();
            }
            line.append((char) current);
            previous = current;
        }
        return line.isEmpty() ? null : line.toString();
    }

    private String streamToFile(InputStream body, Path target, long maxBytes) throws IOException, ImportFailureException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (DigestInputStream input = new DigestInputStream(body, digest);
             var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ImportFailureException("MEDIA_TOO_LARGE", "Source media exceeds maximum size", true);
                }
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void validateMediaContentType(String contentType) throws ImportFailureException {
        if (contentType.startsWith("text/")
                || contentType.equals("application/json")
                || contentType.equals("application/xml")
                || contentType.equals("text/html")) {
            throw new ImportFailureException("UNSUPPORTED_MEDIA", "Source did not return media content", true);
        }
    }

    private String originalFilename(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? null : name;
    }

    private String containerFormat(String contentType) {
        int slash = contentType.indexOf('/');
        return slash < 0 ? contentType : contentType.substring(slash + 1);
    }

    private record DownloadResponse(int status, Map<String, List<String>> headers, InputStream body) {
        Optional<String> firstHeader(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
        }

        void close() throws IOException {
            body.close();
        }
    }

    private static final class ClosingInputStream extends InputStream {
        private final InputStream delegate;
        private final Socket socket;

        private ClosingInputStream(InputStream delegate, Socket socket) {
            this.delegate = delegate;
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                socket.close();
            }
        }
    }

    private final class ChunkedInputStream extends InputStream {
        private final InputStream input;
        private long remaining;
        private boolean finished;

        private ChunkedInputStream(InputStream input) {
            this.input = input;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished) {
                return -1;
            }
            if (remaining == 0) {
                String chunkHeader = readAsciiLine(input);
                if (chunkHeader == null) {
                    throw new IOException("Unexpected end of chunked response");
                }
                int extension = chunkHeader.indexOf(';');
                String sizeText = extension < 0 ? chunkHeader : chunkHeader.substring(0, extension);
                remaining = Long.parseLong(sizeText.trim(), 16);
                if (remaining == 0) {
                    while (true) {
                        String trailer = readAsciiLine(input);
                        if (trailer == null || trailer.isEmpty()) {
                            break;
                        }
                    }
                    finished = true;
                    return -1;
                }
            }
            int read = input.read(buffer, offset, (int) Math.min(length, remaining));
            if (read == -1) {
                throw new IOException("Unexpected end of chunked response");
            }
            remaining -= read;
            if (remaining == 0) {
                String delimiter = readAsciiLine(input);
                if (delimiter == null || !delimiter.isEmpty()) {
                    throw new IOException("Invalid chunk delimiter");
                }
            }
            return read;
        }
    }
}
