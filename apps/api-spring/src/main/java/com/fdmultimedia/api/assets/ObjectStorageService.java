package com.fdmultimedia.api.assets;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class ObjectStorageService {

    private final StorageProperties properties;
    private final Clock clock;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    public ObjectStorageService(StorageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.s3Client = s3Client(properties.getEndpoint(), properties);
        this.presigner = presigner(properties.getPublicEndpoint(), properties);
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (NoSuchBucketException ex) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
            s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
        }
    }

    public String objectKey(MediaAsset asset) {
        return "workspaces/" + asset.getWorkspace().getId() + "/assets/" + asset.getId() + "/original";
    }

    public StorageAccess presignedPut(String key) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignedUploadTtl())
                .putObjectRequest(request)
                .build();
        String url = presigner.presignPutObject(presignRequest).url().toString();
        return new StorageAccess(url, properties.getBucket(), key, Instant.now(clock).plus(properties.getPresignedUploadTtl()));
    }

    public String bucket() {
        return properties.getBucket();
    }

    public StorageAccess presignedGet(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignedDownloadTtl())
                .getObjectRequest(request)
                .build();
        String url = presigner.presignGetObject(presignRequest).url().toString();
        return new StorageAccess(url, properties.getBucket(), key, Instant.now(clock).plus(properties.getPresignedDownloadTtl()));
    }

    private S3Client s3Client(URI endpoint, StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private S3Presigner presigner(URI endpoint, StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.getAccessKey(),
                properties.getSecretKey()));
    }
}
