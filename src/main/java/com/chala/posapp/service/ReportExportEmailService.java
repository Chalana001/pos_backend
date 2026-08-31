package com.chala.posapp.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.report-exports.email.enabled", havingValue = "true")
public class ReportExportEmailService {
    private final JavaMailSender mailSender;

    public void send(String recipient, String fileName, byte[] content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(recipient);
            helper.setSubject("Scheduled POS report: " + fileName);
            helper.setText("Your requested POS report is attached.");
            helper.addAttachment(fileName, () -> new java.io.ByteArrayInputStream(content), ReportExportJobService.XLSX_CONTENT_TYPE);
            mailSender.send(message);
        } catch (Exception error) {
            throw new IllegalStateException("Could not deliver report email", error);
        }
    }
}
