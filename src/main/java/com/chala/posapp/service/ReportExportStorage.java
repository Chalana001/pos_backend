package com.chala.posapp.service;

public interface ReportExportStorage {
    String store(String tenantId, String fileName, byte[] content);
    byte[] read(String storageKey);
    void delete(String storageKey);
}
