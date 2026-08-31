package com.chala.posapp.audit;

import com.chala.posapp.entity.AuditLog;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.AuditLogRepository;
import com.chala.posapp.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;

/**
 * MISS-03: AOP advice that intercepts every @Audited method and writes an
 * AuditLog row after successful return.
 *
 * We use {@code @AfterReturning} (not @Around) so that:
 *  1. Only successful operations are audited (no partial-failure noise).
 *  2. The audit write happens inside the SAME transaction via Spring's
 *     AuditLogRepository — if the outer TX rolls back, the audit is also rolled
 *     back (consistent).
 *
 * ID and summary values are extracted using SpEL against the method parameters,
 * so no reflection on the return value is needed.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditAspect {

    private final AuditLogRepository auditLogRepository;
    private final SecurityUtils securityUtils;

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    @AfterReturning("@annotation(audited)")
    public void afterAuditedMethod(JoinPoint jp, Audited audited) {
        try {
            User actor = tryGetCurrentUser();

            EvaluationContext ctx = buildSpelContext(jp);

            Long   entityId = evalLong(audited.idExpression(), ctx);
            String summary  = evalString(audited.summaryExpression(), ctx);

            AuditLog log = AuditLog.builder()
                    .actorUsername(actor != null ? actor.getUsername() : "SYSTEM")
                    .actorUserId(actor != null ? actor.getId() : null)
                    .actorRole(actor != null ? actor.getRole().name() : null)
                    .entityType(audited.entity())
                    .entityId(entityId)
                    .action(audited.action())
                    .branchId(actor != null ? actor.getBranchId() : null)
                    .summary(summary)
                    .ipAddress(resolveIp())
                    .performedAt(LocalDateTime.now())
                    .build();

            auditLogRepository.save(log);

        } catch (Exception e) {
            // Audit failure must NEVER break the business operation
            lombok.extern.slf4j.Slf4j.class.getName(); // suppress unused import
            // Using standard Java logger to avoid Lombok dependency in catch block
            System.err.println("[AUDIT] Failed to write audit log: " + e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private User tryGetCurrentUser() {
        try {
            return securityUtils.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private EvaluationContext buildSpelContext(JoinPoint jp) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        Object[] args = jp.getArgs();
        MethodSignature sig = (MethodSignature) jp.getSignature();
        Method method = sig.getMethod();
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            ctx.setVariable(params[i].getName(), args[i]);
        }
        return ctx;
    }

    private Long evalLong(String expr, EvaluationContext ctx) {
        if (expr == null || expr.isBlank()) return null;
        try {
            Object val = PARSER.parseExpression(expr).getValue(ctx);
            if (val instanceof Number n) return n.longValue();
            if (val instanceof String s) return Long.parseLong(s);
        } catch (Exception ignored) {}
        return null;
    }

    private String evalString(String expr, EvaluationContext ctx) {
        if (expr == null || expr.isBlank()) return null;
        try {
            Object val = PARSER.parseExpression(expr).getValue(ctx);
            return val != null ? val.toString() : null;
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
