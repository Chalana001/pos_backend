package com.chala.posapp.service;

import com.chala.posapp.entity.BillingRecord;
import com.chala.posapp.entity.SubscriptionInvoice;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.SubscriptionInvoiceRepository;
import com.chala.posapp.repository.BillingRecordRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Subscription invoices: the document a shop owner is sent for a payment.
 *
 * <p>The PDF is rendered from hand-built XHTML through openhtmltopdf, the same library the POS
 * receipt/invoice path already uses, so there is no new dependency and no second PDF stack to
 * keep working.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionInvoiceService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final SubscriptionInvoiceRepository invoiceRepository;
    private final BillingRecordRepository billingRecordRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SuperAdminAuditService auditService;

    @Value("${app.invoice.issuer-name:POS Platform}")
    private String issuerName;

    @Value("${app.invoice.issuer-address:}")
    private String issuerAddress;

    @Value("${app.invoice.issuer-contact:}")
    private String issuerContact;

    @Value("${app.invoice.currency-prefix:Rs.}")
    private String currencyPrefix;

    @Value("${app.invoice.tax-percent:0}")
    private double taxPercent;

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public List<SubscriptionInvoice> forTenant(String tenantId) {
        return invoiceRepository.findByTenantIdOrderByIssuedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public SubscriptionInvoice get(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
    }

    // ----------------------------------------------------------------- issue

    /**
     * Creates the invoice for a billing record. Idempotent: asking twice returns the invoice
     * that already exists rather than issuing a duplicate for the same payment.
     */
    @Transactional
    public SubscriptionInvoice issueFor(Long billingRecordId, String notes) {
        var existing = invoiceRepository.findByBillingRecordId(billingRecordId);
        if (existing.isPresent()) {
            return existing.get();
        }

        BillingRecord record = billingRecordRepository.findById(billingRecordId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing record not found"));

        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(record.getTenantId())
                .orElse(null);

        double subtotal = record.getGrossAmount() > 0 ? record.getGrossAmount() : record.getAmount();
        double discount = record.getDiscountAmount();
        double net = subtotal - discount;
        double tax = taxPercent > 0 ? round(net * (taxPercent / 100.0)) : 0;

        SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                .invoiceNo(nextInvoiceNo())
                .tenantId(record.getTenantId())
                .shopName(record.getShopName())
                .billingRecordId(record.getId())
                .planName(subscription != null && subscription.getPlan() != null
                        ? subscription.getPlan().getName() : null)
                .periodStart(record.getCreatedAt().toLocalDate())
                .periodEnd(subscription != null ? subscription.getValidUntil().toLocalDate() : null)
                .subtotal(round(subtotal))
                .discountAmount(round(discount))
                .taxAmount(tax)
                .total(round(net + tax))
                .status(SubscriptionInvoice.Status.PAID)
                .notes(notes)
                .billedToName(subscription != null ? subscription.getShopName() : record.getShopName())
                .billedToEmail(subscription != null ? subscription.getContactEmail() : null)
                .billedToPhone(subscription != null ? subscription.getContactPhone() : null)
                .issuedBy(currentActor())
                .issuedAt(LocalDateTime.now())
                .paidAt(record.getCreatedAt())
                .build();

        invoiceRepository.save(invoice);
        auditService.record(currentActor(), "INVOICE_ISSUED", SuperAdminAuditService.TARGET_SHOP,
                record.getTenantId(),
                "Issued " + invoice.getInvoiceNo() + " for " + record.getShopName()
                        + " (" + currencyPrefix + " " + invoice.getTotal() + ")");
        return invoice;
    }

    @Transactional
    public SubscriptionInvoice voidInvoice(Long id, String reason) {
        SubscriptionInvoice invoice = get(id);
        if (invoice.getStatus() == SubscriptionInvoice.Status.VOID) {
            return invoice;
        }
        invoice.setStatus(SubscriptionInvoice.Status.VOID);
        invoice.setNotes(reason);
        invoiceRepository.save(invoice);

        auditService.record(currentActor(), "INVOICE_VOIDED", SuperAdminAuditService.TARGET_SHOP,
                invoice.getTenantId(),
                "Voided " + invoice.getInvoiceNo() + (reason != null ? " — " + reason : ""));
        return invoice;
    }

    /**
     * INV-{year}-{sequence}. The sequence restarts each year and is read back from the table,
     * so a gap from a rolled-back transaction is skipped rather than reused.
     */
    private String nextInvoiceNo() {
        String year = String.valueOf(LocalDate.now().getYear());
        Integer max = invoiceRepository.maxSequenceForYear(year);
        int next = (max == null ? 0 : max) + 1;
        return String.format("INV-%s-%04d", year, next);
    }

    // ------------------------------------------------------------------- PDF

    @Transactional(readOnly = true)
    public byte[] renderPdf(Long id) {
        SubscriptionInvoice invoice = get(id);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(buildHtml(invoice), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception exception) {
            log.error("Failed to render invoice {}", invoice.getInvoiceNo(), exception);
            throw new BadRequestException("Could not render this invoice as a PDF.");
        }
    }

    private String buildHtml(SubscriptionInvoice invoice) {
        String voidBanner = invoice.getStatus() == SubscriptionInvoice.Status.VOID
                ? "<div class='void'>VOID</div>"
                : "";

        StringBuilder rows = new StringBuilder();
        rows.append(row(describeLine(invoice), money(invoice.getSubtotal())));
        if (invoice.getDiscountAmount() > 0) {
            rows.append(row("Discount", "-" + money(invoice.getDiscountAmount())));
        }
        if (invoice.getTaxAmount() > 0) {
            rows.append(row("Tax (" + trimNumber(taxPercent) + "%)", money(invoice.getTaxAmount())));
        }

        return """
                <html><head><meta charset="UTF-8"/><style>
                  @page { size: A4; margin: 18mm; }
                  body { font-family: sans-serif; font-size: 11pt; color: #111; }
                  .head { border-bottom: 2px solid #111; padding-bottom: 10px; margin-bottom: 18px; }
                  .issuer { font-size: 15pt; font-weight: bold; }
                  .muted { color: #666; font-size: 9pt; }
                  .title { float: right; text-align: right; }
                  .title h1 { margin: 0; font-size: 20pt; letter-spacing: 1px; }
                  .cols { width: 100%%; margin-bottom: 18px; }
                  .cols td { vertical-align: top; width: 50%%; font-size: 10pt; }
                  .label { text-transform: uppercase; font-size: 8pt; color: #888; letter-spacing: .5px; }
                  table.lines { width: 100%%; border-collapse: collapse; margin-top: 6px; }
                  table.lines th { text-align: left; font-size: 8pt; text-transform: uppercase;
                                   color: #888; border-bottom: 1px solid #ccc; padding: 6px 0; }
                  table.lines td { padding: 8px 0; border-bottom: 1px solid #eee; }
                  .right { text-align: right; }
                  .total td { font-weight: bold; font-size: 13pt; border-top: 2px solid #111; border-bottom: none; }
                  .void { position: absolute; top: 40%%; left: 25%%; font-size: 70pt; color: rgba(200,0,0,.18);
                          font-weight: bold; letter-spacing: 8px; }
                  .foot { margin-top: 30px; font-size: 8pt; color: #888; border-top: 1px solid #eee; padding-top: 8px; }
                </style></head><body>
                  %s
                  <div class="head">
                    <div class="title"><h1>INVOICE</h1><div class="muted">%s</div></div>
                    <div class="issuer">%s</div>
                    <div class="muted">%s</div>
                    <div class="muted">%s</div>
                  </div>

                  <table class="cols"><tr>
                    <td><div class="label">Billed to</div>
                        <strong>%s</strong><br/><span class="muted">%s</span><br/>
                        <span class="muted">%s</span><br/><span class="muted">%s</span></td>
                    <td class="right"><div class="label">Issued</div>%s<br/>
                        <div class="label" style="margin-top:8px">Status</div>%s</td>
                  </tr></table>

                  <table class="lines">
                    <tr><th>Description</th><th class="right">Amount</th></tr>
                    %s
                    <tr class="total"><td>Total</td><td class="right">%s</td></tr>
                  </table>

                  <div class="foot">%s</div>
                </body></html>
                """.formatted(
                voidBanner,
                escape(invoice.getInvoiceNo()),
                escape(issuerName),
                escape(issuerAddress),
                escape(issuerContact),
                escape(invoice.getBilledToName()),
                escape(nullToDash(invoice.getTenantId())),
                escape(nullToDash(invoice.getBilledToEmail())),
                escape(nullToDash(invoice.getBilledToPhone())),
                invoice.getIssuedAt().toLocalDate().format(DATE),
                invoice.getStatus().name(),
                rows,
                money(invoice.getTotal()),
                escape(invoice.getNotes() == null ? "Thank you for your business." : invoice.getNotes()));
    }

    /** Returns markup, so every database value inside it is escaped before being appended. */
    private String describeLine(SubscriptionInvoice invoice) {
        StringBuilder text = new StringBuilder();
        text.append(invoice.getPlanName() == null
                ? "Subscription"
                : escape(invoice.getPlanName()) + " subscription");
        if (invoice.getPeriodStart() != null && invoice.getPeriodEnd() != null) {
            text.append("<br/><span class='muted'>")
                .append(invoice.getPeriodStart().format(DATE))
                .append(" — ")
                .append(invoice.getPeriodEnd().format(DATE))
                .append("</span>");
        }
        return text.toString();
    }

    private String row(String label, String amount) {
        return "<tr><td>" + label + "</td><td class='right'>" + amount + "</td></tr>";
    }

    private String money(double value) {
        return currencyPrefix + " " + String.format("%,.2f", value);
    }

    private String trimNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** The description line is built here, so anything from the database is escaped first. */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
