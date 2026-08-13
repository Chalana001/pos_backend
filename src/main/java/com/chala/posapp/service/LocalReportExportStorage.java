package com.chala.posapp.service;

import com.chala.posapp.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "app.report-exports.storage", havingValue = "local", matchIfMissing = true)
public class LocalReportExportStorage implements ReportExportStorage {
    private final Path root;

    public LocalReportExportStorage(@Value("${app.report-exports.directory:report-exports}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public String store(String tenantId, String fileName, byte[] content) {
        if (!tenantId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid tenant storage segment");
        if (!fileName.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid export file name");
        try {
            Path file = root.resolve(tenantId).resolve(fileName).normalize();
            if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid export storage key");
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IOException error) {
            throw new IllegalStateException("Could not store report export", error);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException error) {
            throw new ResourceNotFoundException("Report export file is no longer available");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException error) {
            throw new IllegalStateException("Could not delete report export", error);
        }
    }

    private Path resolve(String storageKey) {
        Path file = root.resolve(storageKey).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid export storage key");
        return file;
    }
}
