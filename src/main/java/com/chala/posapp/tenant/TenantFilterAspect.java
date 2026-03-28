package com.chala.posapp.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("this(org.springframework.data.repository.Repository)")
    public void enableTenantFilter() {
        String tenantId = TenantContext.getTenant();

        if (tenantId != null && !tenantId.equalsIgnoreCase("MASTER")) {
            Session session = entityManager.unwrap(Session.class);
            if (session != null) {
                session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
            }
        }
    }
}