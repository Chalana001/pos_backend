package com.chala.posapp.rbac.shadow;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShadowRequestOutcomeFilterConfig {
    @Bean
    public FilterRegistrationBean<ShadowRequestOutcomeFilter> shadowRequestOutcomeFilterRegistration(
            ShadowRequestOutcomeFilter filter) {
        FilterRegistrationBean<ShadowRequestOutcomeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Integer.MIN_VALUE);
        registration.addUrlPatterns("/*");
        registration.setName("shadowRequestOutcomeFilter");
        return registration;
    }
}
