package com.chala.posapp.repository;

import com.chala.posapp.entity.SubscriptionInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriptionInvoiceRepository
        extends JpaRepository<SubscriptionInvoice, Long>, JpaSpecificationExecutor<SubscriptionInvoice> {

    Optional<SubscriptionInvoice> findByInvoiceNo(String invoiceNo);

    List<SubscriptionInvoice> findByTenantIdOrderByIssuedAtDesc(String tenantId);

    Optional<SubscriptionInvoice> findByBillingRecordId(Long billingRecordId);

    Page<SubscriptionInvoice> findAllByOrderByIssuedAtDesc(Pageable pageable);

    /** Highest sequence issued in a given year, used to build the next invoice number. */
    @Query(value = "SELECT MAX(CAST(SUBSTRING_INDEX(invoice_no, '-', -1) AS UNSIGNED)) "
            + "FROM subscription_invoices WHERE invoice_no LIKE CONCAT('INV-', :year, '-%')",
            nativeQuery = true)
    Integer maxSequenceForYear(@Param("year") String year);
}
