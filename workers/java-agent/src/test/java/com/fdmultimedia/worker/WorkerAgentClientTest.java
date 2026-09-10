package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerAgentClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsSuccessfulJobResult() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/jobs/00000000-0000-4000-8000-000000000001/complete", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                "WorkerToken credential.secret");

        client.complete(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "machine-1",
                Map.of("message", "done"));

        assertTrue(body.get().contains("\"machineIdentifier\":\"machine-1\""));
        assertTrue(body.get().contains("\"result\":{\"message\":\"done\"}"));
    }

    @Test
    void surfacesServerUnavailableForRetryLoop() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/jobs/claim", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                "WorkerToken credential.secret");

        assertThrows(IOException.class, () -> client.claim("machine-1"));
    }

    @Test
    void claimAdvertisesSupportedJobTypes() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/jobs/claim", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"available\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                "WorkerToken credential.secret");

        client.claim("machine-1");

        assertTrue(body.get().contains("\"machineIdentifier\":\"machine-1\""));
        assertTrue(body.get().contains("\"supportedJobTypes\":[\"SYSTEM_TEST\",\"IMPORT_MEDIA\"]"));
    }
}
