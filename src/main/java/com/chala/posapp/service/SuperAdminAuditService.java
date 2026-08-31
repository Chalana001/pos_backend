package com.chala.posapp.service;

import com.chala.posapp.entity.SuperAdminAuditLog;
import com.chala.posapp.repository.SuperAdminAuditLogRepository;
import com.chala.posapp.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the super admin audit trail.
 *
 * <p>Every call opens its own {@code REQUIRES_NEW} transaction against the control-plane
 * database, so an audit write neither joins nor rolls back with the business transaction that
 * triggered it. That is deliberate: if blocking a shop succeeds but the audit insert fails, the
 * block should still stand and the failure should be a log line, not a 500 for the operator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminAuditService {

    public static final String TARGET_SHOP = "SHOP";
    public static final String TARGET_PLAN = "PLAN";
    public static final String TARGET_MODULE = "MODULE";
    public static final String TARGET_SYSTEM = "SYSTEM";

    private final SuperAdminAuditLogRepository auditLogRepository;
    private final PlatformTransactionManager transactionManager;

    public void record(String actor, String action, String targetType, String targetId, String summary) {
        record(actor, action, targetType, targetId, summary, null);
    }

    public void record(String actor, String action, String targetType, String targetId,
                       String summary, String detailsJson) {
        try {
            SuperAdminAuditLog entry = SuperAdminAuditLog.builder()
                    .actor(actor == null ? "unknown" : actor)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .summary(truncate(summary, 500))
                    .details(detailsJson)
                    .ipAddress(currentIpAddress())
                    .build();

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TenantContext.runWith("MASTER", () -> tx.executeWithoutResult(status -> auditLogRepository.save(entry)));
        } catch (Exception exception) {
            // Never let auditing break the action it is recording.
            log.error("Failed to write super admin audit entry: {} {} {}", action, targetType, targetId, exception);
        }
    }

    private String currentIpAddress() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            // Deliberately NOT reading X-Forwarded-For by hand: anyone could set it, which
            // made the recorded IP a field the audited party got to choose. Tomcat resolves
            // the header into getRemoteAddr() only for trusted proxies — see
            // server.forward-headers-strategy in application.properties.
            return truncate(request.getRemoteAddr(), 45);
        } catch (Exception exception) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
