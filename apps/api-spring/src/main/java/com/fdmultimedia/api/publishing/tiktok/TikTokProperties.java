package com.fdmultimedia.api.publishing.tiktok;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "app.publishing.tiktok")
public class TikTokProperties {
 private boolean enabled; private String clientKey="",clientSecret="",oauthRedirectUri="";
 private String authorizeUrl="https://www.tiktok.com/v2/auth/authorize/",apiBaseUrl="https://open.tiktokapis.com";
 private Duration oauthStateTtl=Duration.ofMinutes(10),connectTimeout=Duration.ofSeconds(10),readTimeout=Duration.ofSeconds(30),uploadTimeout=Duration.ofMinutes(10),refreshMargin=Duration.ofMinutes(20);
 private long maxVideoBytes=4_294_967_296L; private int chunkBytes=32*1024*1024;
 public boolean isConfigured(){return enabled&&!clientKey.isBlank()&&!clientSecret.isBlank()&&!oauthRedirectUri.isBlank();}
 public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
 public String getClientKey(){return clientKey;} public void setClientKey(String v){clientKey=v==null?"":v;}
 public String getClientSecret(){return clientSecret;} public void setClientSecret(String v){clientSecret=v==null?"":v;}
 public String getOauthRedirectUri(){return oauthRedirectUri;} public void setOauthRedirectUri(String v){oauthRedirectUri=v==null?"":v;}
 public String getAuthorizeUrl(){return authorizeUrl;} public void setAuthorizeUrl(String v){authorizeUrl=v;}
 public String getApiBaseUrl(){return apiBaseUrl;} public void setApiBaseUrl(String v){apiBaseUrl=v;}
 public Duration getOauthStateTtl(){return oauthStateTtl;} public void setOauthStateTtl(Duration v){oauthStateTtl=v;}
 public Duration getConnectTimeout(){return connectTimeout;} public void setConnectTimeout(Duration v){connectTimeout=v;}
 public Duration getReadTimeout(){return readTimeout;} public void setReadTimeout(Duration v){readTimeout=v;}
 public Duration getUploadTimeout(){return uploadTimeout;} public void setUploadTimeout(Duration v){uploadTimeout=v;}
 public Duration getRefreshMargin(){return refreshMargin;} public void setRefreshMargin(Duration v){refreshMargin=v;}
 public long getMaxVideoBytes(){return maxVideoBytes;} public void setMaxVideoBytes(long v){maxVideoBytes=v;}
 public int getChunkBytes(){return chunkBytes;} public void setChunkBytes(int v){chunkBytes=v;}
}
