package com.chala.posapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportExportConfigurationValidatorTest {
    @Test
    void acceptsDefaultLocalStorageWithEmailDisabled() {
        ReportExportConfigurationValidator validator = validator("local", "", "", false, "", "");
        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void requiresBucketAndRegionForS3Storage() {
        ReportExportConfigurationValidator validator = validator("s3", "", "ap-south-1", false, "", "");
        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void acceptsCompleteS3StorageConfiguration() {
        ReportExportConfigurationValidator validator = validator(" S3 ", "reports", "ap-south-1", false, "", "");
        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void requiresSmtpConfigurationWhenEmailIsEnabled() {
        ReportExportConfigurationValidator validator = validator("local", "", "", true, "", "");
        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    private ReportExportConfigurationValidator validator(String storage, String bucket, String region,
                                                          boolean emailEnabled, String mailHost, String mailUsername) {
        ReportExportConfigurationValidator validator = new ReportExportConfigurationValidator();
        ReflectionTestUtils.setField(validator, "storage", storage);
        ReflectionTestUtils.setField(validator, "bucket", bucket);
        ReflectionTestUtils.setField(validator, "region", region);
        ReflectionTestUtils.setField(validator, "emailEnabled", emailEnabled);
        ReflectionTestUtils.setField(validator, "mailHost", mailHost);
        ReflectionTestUtils.setField(validator, "mailUsername", mailUsername);
        ReflectionTestUtils.setField(validator, "mailPassword", "secret");
        ReflectionTestUtils.setField(validator, "mailPort", 587);
        ReflectionTestUtils.setField(validator, "mailAuth", true);
        return validator;
    }
}
