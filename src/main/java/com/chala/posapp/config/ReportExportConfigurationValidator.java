package com.chala.posapp.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class ReportExportConfigurationValidator implements InitializingBean {
    @Value("${app.report-exports.storage:local}") private String storage;
    @Value("${app.report-exports.s3.bucket:}") private String bucket;
    @Value("${app.report-exports.s3.region:}") private String region;
    @Value("${app.report-exports.email.enabled:false}") private boolean emailEnabled;
    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${spring.mail.password:}") private String mailPassword;
    @Value("${spring.mail.port:587}") private int mailPort;
    @Value("${spring.mail.properties.mail.smtp.auth:true}") private boolean mailAuth;

    @Override
    public void afterPropertiesSet() {
        storage = storage.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("local", "s3").contains(storage)) {
            throw new IllegalStateException("app.report-exports.storage must be local or s3");
        }
        if ("s3".equals(storage) && (bucket.isBlank() || region.isBlank())) {
            throw new IllegalStateException("S3 report storage requires bucket and region");
        }
        if (emailEnabled && (mailHost.isBlank() || mailUsername.isBlank() || mailPort < 1 || mailPort > 65535)) {
            throw new IllegalStateException("Report email delivery requires a valid SMTP host, port and username");
        }
        if (emailEnabled && mailAuth && mailPassword.isBlank()) {
            throw new IllegalStateException("Authenticated report email delivery requires spring.mail.password");
        }
    }
}
