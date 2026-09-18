package com.fdmultimedia.api.publishing.instagram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side Meta/Instagram application configuration. Never contains a
 * user access token — only the app-level identity needed to run the OAuth
 * flow and call the Graph API on the app's own behalf.
 */
@ConfigurationProperties(prefix = "app.publishing.instagram")
public class InstagramProperties {

    private boolean enabled = false;
    private String appId = "";
    private String appSecret = "";
    private String oauthRedirectUri = "";
    /** Publicly reachable origin (e.g. https://app.example.com, no trailing slash) Meta can fetch media from. Required only when enabled. */
    private String publicBaseUrl = "";
    private String graphApiVersion = "v25.0";
    // Overridable only so tests can point the client at a local fake HTTP
    // server instead of real Meta infrastructure. Production deployments
    // should never change these from the official Meta hosts.
    private String oauthAuthorizeBaseUrl = "https://www.instagram.com/oauth/authorize";
    private String oauthTokenExchangeUrl = "https://api.instagram.com/oauth/access_token";
    private String graphApiBaseUrl = "https://graph.instagram.com";
    private Duration oauthStateTtl = Duration.ofMinutes(10);
    private Duration httpConnectTimeout = Duration.ofSeconds(10);
    private Duration httpReadTimeout = Duration.ofSeconds(30);
    private Duration containerPollInterval = Duration.ofSeconds(5);
    private Duration containerProcessingTimeout = Duration.ofMinutes(5);
    private Duration publicMediaUrlTtl = Duration.ofMinutes(30);

    /** True only when {@code enabled} and both app id/secret and a redirect URI are present. */
    public boolean isConfigured() {
        return enabled && !appId.isBlank() && !appSecret.isBlank() && !oauthRedirectUri.isBlank() && !publicBaseUrl.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId == null ? "" : appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret == null ? "" : appSecret; }
    public String getOauthRedirectUri() { return oauthRedirectUri; }
    public void setOauthRedirectUri(String oauthRedirectUri) { this.oauthRedirectUri = oauthRedirectUri == null ? "" : oauthRedirectUri; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl; }
    public String getGraphApiVersion() { return graphApiVersion; }
    public void setGraphApiVersion(String graphApiVersion) { this.graphApiVersion = graphApiVersion; }
    public String getOauthAuthorizeBaseUrl() { return oauthAuthorizeBaseUrl; }
    public void setOauthAuthorizeBaseUrl(String oauthAuthorizeBaseUrl) { this.oauthAuthorizeBaseUrl = oauthAuthorizeBaseUrl; }
    public String getOauthTokenExchangeUrl() { return oauthTokenExchangeUrl; }
    public void setOauthTokenExchangeUrl(String oauthTokenExchangeUrl) { this.oauthTokenExchangeUrl = oauthTokenExchangeUrl; }
    public String getGraphApiBaseUrl() { return graphApiBaseUrl; }
    public void setGraphApiBaseUrl(String graphApiBaseUrl) { this.graphApiBaseUrl = graphApiBaseUrl; }
    public Duration getOauthStateTtl() { return oauthStateTtl; }
    public void setOauthStateTtl(Duration oauthStateTtl) { this.oauthStateTtl = oauthStateTtl; }
    public Duration getHttpConnectTimeout() { return httpConnectTimeout; }
    public void setHttpConnectTimeout(Duration httpConnectTimeout) { this.httpConnectTimeout = httpConnectTimeout; }
    public Duration getHttpReadTimeout() { return httpReadTimeout; }
    public void setHttpReadTimeout(Duration httpReadTimeout) { this.httpReadTimeout = httpReadTimeout; }
    public Duration getContainerPollInterval() { return containerPollInterval; }
    public void setContainerPollInterval(Duration containerPollInterval) { this.containerPollInterval = containerPollInterval; }
    public Duration getContainerProcessingTimeout() { return containerProcessingTimeout; }
    public void setContainerProcessingTimeout(Duration containerProcessingTimeout) { this.containerProcessingTimeout = containerProcessingTimeout; }
    public Duration getPublicMediaUrlTtl() { return publicMediaUrlTtl; }
    public void setPublicMediaUrlTtl(Duration publicMediaUrlTtl) { this.publicMediaUrlTtl = publicMediaUrlTtl; }
}
