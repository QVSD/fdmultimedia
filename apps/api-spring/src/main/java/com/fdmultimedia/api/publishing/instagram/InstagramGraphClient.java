package com.fdmultimedia.api.publishing.instagram;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Isolated Instagram/Meta Graph API HTTP protocol layer. Every raw HTTP call
 * lives here rather than scattered through controllers/services, per the
 * Phase 10B "Instagram Publishing Coordinator" requirement. Base URLs and
 * endpoints are fixed by this class and application configuration only —
 * never influenced by browser-supplied input.
 *
 * <p>Secrets (app secret, access tokens) are sent as POST form-body fields
 * wherever Meta's protocol allows it, specifically so they never appear in a
 * request URI that could end up in an HTTP client debug log. Where Meta's
 * documented protocol only offers a GET endpoint with the token as a query
 * parameter (long-lived token exchange/refresh, container status, account
 * profile), that is unavoidable per the official API shape — this class never
 * logs a built request URI, only the normalized outcome (HTTP status,
 * Meta's own numeric error code, and {@code fbtrace_id}).
 */
@Component
public class InstagramGraphClient {

    private static final Logger log = LoggerFactory.getLogger(InstagramGraphClient.class);
    private static final String REQUIRED_SCOPES = "instagram_business_basic,instagram_business_content_publish";

    private final InstagramProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public InstagramGraphClient(InstagramProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties))
                .build();
    }

    public String authorizationUrl(String state) {
        return query(properties.getOauthAuthorizeBaseUrl())
                .add("client_id", properties.getAppId())
                .add("redirect_uri", properties.getOauthRedirectUri())
                .add("response_type", "code")
                .add("scope", REQUIRED_SCOPES)
                .add("state", state)
                .build();
    }

    public InstagramTokenResult exchangeCodeForShortLivedToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getAppId());
        form.add("client_secret", properties.getAppSecret());
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", properties.getOauthRedirectUri());
        form.add("code", code);
        JsonNode body = postForm(properties.getOauthTokenExchangeUrl(), form);
        JsonNode entry = body.path("data").path(0);
        String accessToken = entry.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new InstagramApiException(InstagramErrorCodes.PUBLISH_FAILED, "Code exchange returned no access token", false);
        }
        // Short-lived tokens are valid ~1 hour; only used immediately to mint a long-lived token.
        return new InstagramTokenResult(accessToken, Instant.now().plusSeconds(3600));
    }

    public InstagramTokenResult exchangeForLongLivedToken(String shortLivedAccessToken) {
        String url = query(properties.getGraphApiBaseUrl() + "/access_token")
                .add("grant_type", "ig_exchange_token")
                .add("client_secret", properties.getAppSecret())
                .add("access_token", shortLivedAccessToken)
                .build();
        return parseTokenResponse(getJson(url));
    }

    public InstagramTokenResult refreshLongLivedToken(String longLivedAccessToken) {
        String url = query(properties.getGraphApiBaseUrl() + "/refresh_access_token")
                .add("grant_type", "ig_refresh_token")
                .add("access_token", longLivedAccessToken)
                .build();
        return parseTokenResponse(getJson(url));
    }

    public InstagramAccountProfile fetchAccountProfile(String instagramUserId, String accessToken) {
        String url = query(properties.getGraphApiBaseUrl() + "/" + properties.getGraphApiVersion() + "/" + instagramUserId)
                .add("fields", "id,username")
                .add("access_token", accessToken)
                .build();
        JsonNode body = getJson(url);
        String id = body.path("id").asText(null);
        String username = body.path("username").asText(null);
        if (id == null || id.isBlank()) {
            throw new InstagramApiException(InstagramErrorCodes.PUBLISH_FAILED, "Account profile lookup returned no id", false);
        }
        return new InstagramAccountProfile(id, username == null ? id : username);
    }

    public String createMediaContainer(String instagramUserId, String accessToken, String videoUrl, String caption) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("media_type", "REELS");
        form.add("video_url", videoUrl);
        form.add("share_to_feed", "true");
        if (caption != null && !caption.isBlank()) {
            form.add("caption", caption);
        }
        form.add("access_token", accessToken);
        String url = properties.getGraphApiBaseUrl() + "/" + properties.getGraphApiVersion() + "/" + instagramUserId + "/media";
        JsonNode body = postForm(url, form);
        String containerId = body.path("id").asText(null);
        if (containerId == null || containerId.isBlank()) {
            throw new InstagramApiException(InstagramErrorCodes.PUBLISH_FAILED, "Container creation returned no id", false);
        }
        return containerId;
    }

    public InstagramContainerStatus fetchContainerStatus(String containerId, String accessToken) {
        String url = query(properties.getGraphApiBaseUrl() + "/" + properties.getGraphApiVersion() + "/" + containerId)
                .add("fields", "status_code")
                .add("access_token", accessToken)
                .build();
        JsonNode body = getJson(url);
        String status = body.path("status_code").asText("");
        try {
            return InstagramContainerStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            log.warn("Unrecognized Instagram container status_code value received");
            return InstagramContainerStatus.IN_PROGRESS;
        }
    }

    public String publishContainer(String instagramUserId, String accessToken, String containerId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("creation_id", containerId);
        form.add("access_token", accessToken);
        String url = properties.getGraphApiBaseUrl() + "/" + properties.getGraphApiVersion() + "/" + instagramUserId + "/media_publish";
        JsonNode body = postForm(url, form);
        String mediaId = body.path("id").asText(null);
        if (mediaId == null || mediaId.isBlank()) {
            throw new InstagramApiException(InstagramErrorCodes.PUBLISH_FAILED, "Publish call returned no media id", false);
        }
        return mediaId;
    }

    private InstagramTokenResult parseTokenResponse(JsonNode body) {
        String accessToken = body.path("access_token").asText(null);
        long expiresInSeconds = body.path("expires_in").asLong(0);
        if (accessToken == null || accessToken.isBlank() || expiresInSeconds <= 0) {
            throw new InstagramApiException(InstagramErrorCodes.PUBLISH_FAILED, "Token response was incomplete", false);
        }
        return new InstagramTokenResult(accessToken, Instant.now().plusSeconds(expiresInSeconds));
    }

    private JsonNode getJson(String url) {
        try {
            String response = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
            return objectMapper.readTree(response);
        } catch (RestClientResponseException ex) {
            throw translate(ex);
        } catch (Exception ex) {
            throw new InstagramApiException(InstagramErrorCodes.TEMPORARY_ERROR, "Instagram request failed: " + ex.getClass().getSimpleName(), true, ex);
        }
    }

    private JsonNode postForm(String url, MultiValueMap<String, String> form) {
        try {
            String response = restClient.post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (RestClientResponseException ex) {
            throw translate(ex);
        } catch (Exception ex) {
            throw new InstagramApiException(InstagramErrorCodes.TEMPORARY_ERROR, "Instagram request failed: " + ex.getClass().getSimpleName(), true, ex);
        }
    }

    private InstagramApiException translate(RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        JsonNode error;
        try {
            error = objectMapper.readTree(ex.getResponseBodyAsString()).path("error");
        } catch (Exception parseFailure) {
            error = null;
        }
        int code = error == null ? -1 : error.path("code").asInt(-1);
        String fbTraceId = error == null ? null : error.path("fbtrace_id").asText(null);
        log.warn("Instagram Graph API error: httpStatus={} errorCode={} fbtraceId={}", status.value(), code, fbTraceId);

        if (code == 190) {
            return new InstagramApiException(InstagramErrorCodes.AUTH_EXPIRED, "Instagram authorization has expired or was revoked", false);
        }
        if (code == 4 || code == 17 || code == 32 || code == 341 || status.value() == 429) {
            return new InstagramApiException(InstagramErrorCodes.RATE_LIMITED, "Instagram rate limit reached", true);
        }
        if (status.is4xxClientError()) {
            return new InstagramApiException(InstagramErrorCodes.MEDIA_REJECTED, "Instagram rejected the request", false);
        }
        return new InstagramApiException(InstagramErrorCodes.TEMPORARY_ERROR, "Instagram returned a temporary error", true);
    }

    private ClientHttpRequestFactory requestFactory(InstagramProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getHttpConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getHttpReadTimeout().toMillis());
        return factory;
    }

    private static QueryBuilder query(String base) {
        return new QueryBuilder(base);
    }

    /** Minimal query-string builder. Base URLs are always fixed constants/config, never browser input. */
    private static final class QueryBuilder {
        private final StringBuilder sb;
        private boolean first = true;

        private QueryBuilder(String base) {
            this.sb = new StringBuilder(base);
        }

        QueryBuilder add(String key, String value) {
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
            return this;
        }

        String build() {
            return sb.toString();
        }
    }
}
