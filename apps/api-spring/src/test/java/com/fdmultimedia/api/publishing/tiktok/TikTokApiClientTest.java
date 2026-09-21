package com.fdmultimedia.api.publishing.tiktok;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class TikTokApiClientTest {
    private HttpServer server;
    private TikTokProperties properties;
    @TempDir Path tempDir;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new TikTokProperties();
        properties.setClientKey("client key"); properties.setClientSecret("secret");
        properties.setOauthRedirectUri("https://app.example/api/social-accounts/tiktok/callback");
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void tearDown() { server.stop(0); }

    @Test void authorizationUrlRequestsOnlyTheMinimalDirectPostScope() {
        String url = new TikTokApiClient(properties, new ObjectMapper()).authorizationUrl("safe-state");
        assertThat(url).contains("scope=video.publish", "state=safe-state")
                .doesNotContain("video.list", "user.info.basic");
    }

    @Test void mapsCreatorInfoAndPreservesProviderRestrictions() {
        json("/v2/post/publish/creator_info/query/", 200, """
            {"data":{"creator_username":"dragos","creator_nickname":"Dragos","privacy_level_options":["SELF_ONLY"],
            "comment_disabled":true,"duet_disabled":false,"stitch_disabled":true,"max_video_post_duration_sec":180},
            "error":{"code":"ok","message":"","log_id":"safe"}}""");
        TikTokModels.CreatorInfo info = new TikTokApiClient(properties, new ObjectMapper()).creatorInfo("not-logged");
        assertThat(info.privacyLevelOptions()).containsExactly("SELF_ONLY");
        assertThat(info.commentDisabled()).isTrue(); assertThat(info.maxVideoPostDurationSec()).isEqualTo(180);
    }

    @Test void scopeFailureIsControlledAndPermanent() {
        json("/v2/post/publish/creator_info/query/", 401, "{\"error\":{\"code\":\"scope_not_authorized\"}}");
        assertThatThrownBy(() -> new TikTokApiClient(properties, new ObjectMapper()).creatorInfo("token"))
                .isInstanceOfSatisfying(TikTokApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("AUTH_REAUTH_REQUIRED"); assertThat(ex.retryable()).isFalse();
                });
    }

    @Test void rejectsMalformedCreatorResponse() {
        json("/v2/post/publish/creator_info/query/", 200, "{\"data\":{},\"error\":{\"code\":\"ok\"}}");
        assertThatThrownBy(() -> new TikTokApiClient(properties, new ObjectMapper()).creatorInfo("token"))
                .isInstanceOf(TikTokApiException.class);
    }

    @Test void chunkPlanNeverProducesATinyTrailingChunkAndFollowsTheFloorFormula() {
        TikTokApiClient client = new TikTokApiClient(properties, new ObjectMapper());
        properties.setChunkBytes(5 * 1024 * 1024);
        assertThat(client.chunkPlan(1_000_000)).containsExactly(1_000_000, 1);
        assertThat(client.chunkPlan(5L * 1024 * 1024)).containsExactly(5L * 1024 * 1024, 1);
        assertThat(client.chunkPlan(3L * 5 * 1024 * 1024)).containsExactly(5L * 1024 * 1024, 3);
        // 2 full chunks plus a 6,000,000-byte remainder: floor(16485760 / 5242880) = 3,
        // so the remainder must be merged into the final chunk rather than sent as a 4th request.
        assertThat(client.chunkPlan(16_485_760L)).containsExactly(5L * 1024 * 1024, 3L);
        assertThatThrownBy(() -> client.chunkPlan(0)).isInstanceOf(TikTokApiException.class);
    }

    @Test void sendChunksExecutesExactlyTheDeclaredChunkPlanWithMatchingContentRanges() throws Exception {
        properties.setChunkBytes(5 * 1024 * 1024);
        long size = 16_485_760L;
        Path file = tempDir.resolve("video.mp4");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) { raf.setLength(size); }
        List<String> ranges = new CopyOnWriteArrayList<>();
        server.createContext("/upload", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Content-Range");
            ranges.add(range);
            long end = Long.parseLong(range.substring(range.indexOf('-') + 1, range.indexOf('/')));
            long total = Long.parseLong(range.substring(range.indexOf('/') + 1));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(end + 1 == total ? 201 : 206, -1);
            exchange.close();
        });
        TikTokApiClient client = new TikTokApiClient(properties, new ObjectMapper());
        long[] plan = client.chunkPlan(size);

        client.sendChunks(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/upload"), file, "video/mp4");

        assertThat(ranges).hasSize((int) plan[1]);
        assertThat(ranges).containsExactly(
                "bytes 0-5242879/16485760",
                "bytes 5242880-10485759/16485760",
                "bytes 10485760-16485759/16485760");
    }

    private void json(String path, int status, String body) {
        server.createContext(path, exchange -> { byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
    }
}
