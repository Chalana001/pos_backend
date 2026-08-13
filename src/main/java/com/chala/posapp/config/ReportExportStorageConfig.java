package com.chala.posapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class ReportExportStorageConfig {
    @Bean
    @ConditionalOnProperty(name = "app.report-exports.storage", havingValue = "s3")
    S3Client reportExportS3Client(
            @Value("${app.report-exports.s3.region}") String region,
            @Value("${app.report-exports.s3.endpoint:}") String endpoint,
            @Value("${app.report-exports.s3.access-key:}") String accessKey,
            @Value("${app.report-exports.s3.secret-key:}") String secretKey) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank()) builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(!endpoint.isBlank()).build());
        return builder.build();
    }
}
