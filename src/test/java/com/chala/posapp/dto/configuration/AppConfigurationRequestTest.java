package com.chala.posapp.dto.configuration;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigurationRequestTest {
    @Test
    void zeroClearsHeldBillLimitButNegativeLimitIsInvalid() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new AppConfigurationRequest();
            request.setQuickSaleHeldBillsMax(0);
            assertEquals(0, validator.validate(request).size());
            request.setQuickSaleHeldBillsMax(-1);
            assertEquals(1, validator.validate(request).size());
        }
    }
}
