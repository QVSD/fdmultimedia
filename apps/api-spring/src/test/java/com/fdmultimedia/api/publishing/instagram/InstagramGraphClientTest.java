package com.fdmultimedia.api.publishing.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the full Instagram Graph API protocol against a local fake HTTP
 * server, never a real Meta endpoint — this is the Level A provider-contract
 * coverage the Phase 10B spec requires. {@link InstagramProperties}'s
 * overridable base URLs exist specifically so this test can point the real
 * client code at {@link #server} instead of production Meta hosts.
 */
class InstagramGraphClientTest {

    private HttpServer server;
    private InstagramProperties properties;
    private InstagramGraphClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new InstagramProperties();
        properties.setAppId("test-app-id");
        properties.setAppSecret("test-app-secret");
        properties.setOauthRedirectUri("https://app.example.test/api/social-accounts/instagram/callback");
        properties.setGraphApiVersion("v25.0");
        properties.setOauthAuthorizeBaseUrl(baseUrl() + "/oauth/authorize");
        properties.setOauthTokenExchangeUrl(baseUrl() + "/oauth/access_token");
        properties.setGraphApiBaseUrl(baseUrl() + "/graph");
        client = new InstagramGraphClient(properties, objectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buildsAuthorizationUrlWithRequiredParameters() {
        String url = client.authorizationUrl("raw-state-value");

        assertThat(url).startsWith(baseUrl() + "/oauth/authorize?");
        assertThat(url).contains("client_id=test-app-id");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("state=raw-state-value");
        assertThat(url).contains("instagram_business_content_publish");
    }

    @Test
    void exchangesCodeForShortLivedToken() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/oauth/access_token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, "{\"data\":[{\"access_token\":\"short-lived-token\",\"user_id\":\"12345\",\"permissions\":\"instagram_business_basic\"}]}");
        });

        InstagramTokenResult result = client.exchangeCodeForShortLivedToken("auth-code-value");

        assertThat(result.accessToken()).isEqualTo("short-lived-token");
        assertThat(requestBody.get()).contains("grant_type=authorization_code");
        assertThat(requestBody.get()).contains("code=auth-code-value");
        assertThat(requestBody.get()).contains("client_secret=test-app-secret");
    }

    @Test
    void exchangesForLongLivedToken() throws Exception {
        server.createContext("/graph/access_token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"long-lived-token\",\"token_type\":\"bearer\",\"expires_in\":5184000}"));

        InstagramTokenResult result = client.exchangeForLongLivedToken("short-lived-token");

        assertThat(result.accessToken()).isEqualTo("long-lived-token");
        assertThat(result.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(5_000_000));
    }

    @Test
    void refreshesLongLivedToken() throws Exception {
        server.createContext("/graph/refresh_access_token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"refreshed-token\",\"token_type\":\"bearer\",\"expires_in\":5184000}"));

        InstagramTokenResult result = client.refreshLongLivedToken("long-lived-token");

        assertThat(result.accessToken()).isEqualTo("refreshed-token");
    }

    @Test
    void fetchesAccountProfile() throws Exception {
        server.createContext("/graph/v25.0/me", exchange ->
                respondJson(exchange, 200, "{\"id\":\"17841400000000000\",\"username\":\"creator_handle\"}"));

        InstagramAccountProfile profile = client.fetchAccountProfile("me", "token");

        assertThat(profile.id()).isEqualTo("17841400000000000");
        assertThat(profile.username()).isEqualTo("creator_handle");
    }

    @Test
    void createsMediaContainerAsReelsWithVideoUrlAndCaption() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/graph/v25.0/17841400000000000/media", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, "{\"id\":\"container-abc\"}");
        });

        String containerId = client.createMediaContainer(
                "17841400000000000", "token", "https://app.example.test/api/public-media/tok123", "Hello world");

        assertThat(containerId).isEqualTo("container-abc");
        assertThat(requestBody.get()).contains("media_type=REELS");
        assertThat(requestBody.get()).contains("share_to_feed=true");
        assertThat(requestBody.get()).contains("caption=Hello+world");
    }

    @Test
    void fetchesContainerStatus() throws Exception {
        server.createContext("/graph/v25.0/container-abc", exchange ->
                respondJson(exchange, 200, "{\"status_code\":\"FINISHED\"}"));

        assertThat(client.fetchContainerStatus("container-abc", "token")).isEqualTo(InstagramContainerStatus.FINISHED);
    }

    @Test
    void unrecognizedStatusCodeIsTreatedAsInProgress() throws Exception {
        server.createContext("/graph/v25.0/container-abc", exchange ->
                respondJson(exchange, 200, "{\"status_code\":\"SOMETHING_NEW\"}"));

        assertThat(client.fetchContainerStatus("container-abc", "token")).isEqualTo(InstagramContainerStatus.IN_PROGRESS);
    }

    @Test
    void publishesContainer() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/graph/v25.0/17841400000000000/media_publish", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, "{\"id\":\"media-xyz\"}");
        });

        String mediaId = client.publishContainer("17841400000000000", "token", "container-abc");

        assertThat(mediaId).isEqualTo("media-xyz");
        assertThat(requestBody.get()).contains("creation_id=container-abc");
    }

    @Test
    void expiredTokenErrorMapsToAuthExpired() throws Exception {
        server.createContext("/graph/v25.0/container-abc", exchange ->
                respondJson(exchange, 400, "{\"error\":{\"message\":\"Error validating access token\",\"type\":\"OAuthException\",\"code\":190,\"fbtrace_id\":\"abc123\"}}"));

        assertThatThrownBy(() -> client.fetchContainerStatus("container-abc", "token"))
                .isInstanceOf(InstagramApiException.class)
                .extracting(ex -> ((InstagramApiException) ex).code())
                .isEqualTo(InstagramErrorCodes.AUTH_EXPIRED);
    }

    @Test
    void rateLimitErrorIsRetryable() throws Exception {
        server.createContext("/graph/v25.0/container-abc", exchange ->
                respondJson(exchange, 400, "{\"error\":{\"message\":\"Rate limited\",\"type\":\"OAuthException\",\"code\":4,\"fbtrace_id\":\"abc123\"}}"));

        assertThatThrownBy(() -> client.fetchContainerStatus("container-abc", "token"))
                .isInstanceOf(InstagramApiException.class)
                .satisfies(ex -> {
                    InstagramApiException apiEx = (InstagramApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(InstagramErrorCodes.RATE_LIMITED);
                    assertThat(apiEx.retryable()).isTrue();
                });
    }

    @Test
    void genericClientErrorMapsToMediaRejected() throws Exception {
        server.createContext("/graph/v25.0/17841400000000000/media", exchange ->
                respondJson(exchange, 400, "{\"error\":{\"message\":\"Invalid video format\",\"type\":\"OAuthException\",\"code\":2207026,\"fbtrace_id\":\"abc123\"}}"));

        assertThatThrownBy(() -> client.createMediaContainer("17841400000000000", "token", "https://example.test/media", null))
                .isInstanceOf(InstagramApiException.class)
                .satisfies(ex -> {
                    InstagramApiException apiEx = (InstagramApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(InstagramErrorCodes.MEDIA_REJECTED);
                    assertThat(apiEx.retryable()).isFalse();
                });
    }

    @Test
    void serverErrorMapsToTemporaryAndIsRetryable() throws Exception {
        server.createContext("/graph/v25.0/container-abc", exchange ->
                respondJson(exchange, 503, "{\"error\":{\"message\":\"Service unavailable\",\"type\":\"OAuthException\",\"code\":2,\"fbtrace_id\":\"abc123\"}}"));

        assertThatThrownBy(() -> client.fetchContainerStatus("container-abc", "token"))
                .isInstanceOf(InstagramApiException.class)
                .satisfies(ex -> {
                    InstagramApiException apiEx = (InstagramApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(InstagramErrorCodes.TEMPORARY_ERROR);
                    assertThat(apiEx.retryable()).isTrue();
                });
    }

    @Test
    void malformedJsonResponseIsTreatedAsTemporaryError() throws Exception {
        server.createContext("/graph/v25.0/container-abc", exchange ->
                respondJson(exchange, 200, "not-json-at-all"));

        assertThatThrownBy(() -> client.fetchContainerStatus("container-abc", "token"))
                .isInstanceOf(InstagramApiException.class);
    }

    private void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }
}
