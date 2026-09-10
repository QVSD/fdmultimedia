package com.fdmultimedia.api.assets;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private URI endpoint = URI.create("http://localhost:9000");
    private URI publicEndpoint = URI.create("http://localhost:9000");
    private String region = "us-east-1";
    private String bucket = "media-assets";
    private String accessKey = "";
    private String secretKey = "";
    private Duration presignedUploadTtl = Duration.ofMinutes(15);
    private Duration presignedDownloadTtl = Duration.ofMinutes(5);

    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public URI getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(URI publicEndpoint) { this.publicEndpoint = publicEndpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public Duration getPresignedUploadTtl() { return presignedUploadTtl; }
    public void setPresignedUploadTtl(Duration presignedUploadTtl) { this.presignedUploadTtl = presignedUploadTtl; }
    public Duration getPresignedDownloadTtl() { return presignedDownloadTtl; }
    public void setPresignedDownloadTtl(Duration presignedDownloadTtl) { this.presignedDownloadTtl = presignedDownloadTtl; }
}
