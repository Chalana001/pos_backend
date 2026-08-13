package com.chala.posapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
@ConditionalOnProperty(name = "app.report-exports.storage", havingValue = "s3")
public class S3ReportExportStorage implements ReportExportStorage {
    private final S3Client s3Client;
    private final String bucket;

    public S3ReportExportStorage(S3Client s3Client, @Value("${app.report-exports.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String store(String tenantId, String fileName, byte[] content) {
        String key = "report-exports/" + tenantId + "/" + fileName;
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(ReportExportJobService.XLSX_CONTENT_TYPE).build(), RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public byte[] read(String storageKey) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(storageKey).build()).asByteArray();
    }

    @Override
    public void delete(String storageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
    }
}
