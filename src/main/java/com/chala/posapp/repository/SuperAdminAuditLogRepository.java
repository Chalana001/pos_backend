package com.chala.posapp.repository;

import com.chala.posapp.entity.SuperAdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface SuperAdminAuditLogRepository
        extends JpaRepository<SuperAdminAuditLog, Long>, JpaSpecificationExecutor<SuperAdminAuditLog> {

    List<SuperAdminAuditLog> findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);

    List<SuperAdminAuditLog> findTop15ByOrderByCreatedAtDesc();
}
