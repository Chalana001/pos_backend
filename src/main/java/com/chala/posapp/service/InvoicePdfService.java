package com.chala.posapp.service;

import com.chala.posapp.dto.order.OrderItemResponse;
import com.chala.posapp.dto.order.OrderResponse;
import com.chala.posapp.dto.receipt.ReceiptSettingsResponse;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.PrintTemplateType;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final String DEFAULT_STORE_NAME = "POS SYSTEM";
    private static final String DEFAULT_CUSTOMER_NAME = "Walk-in Customer";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm a");
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-LK"));

    private final OrderService orderService;
    private final ReceiptSettingsService receiptSettingsService;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final TemplateEngine templateEngine;

    public byte[] generateInvoicePdf(String invoiceNo) {
        OrderResponse order = orderService.getOrder(invoiceNo);
        ReceiptSettingsResponse settings = receiptSettingsService.getSettings(order.getBranchId(), PrintTemplateType.A4);

        String tenantId = TenantContext.getTenant();
        String storeName = tenantSubscriptionRepository.findByTenantId(tenantId)
                .map(TenantSubscription::getShopName)
                .filter(this::hasText)
                .orElse(DEFAULT_STORE_NAME);

        String cashierName = userRepository.findById(order.getCashierUserId())
                .map(User::getUsername)
                .filter(this::hasText)
                .orElse("Cashier");

        String customerPhone = null;
        if (order.getCustomerId() != null) {
            customerPhone = customerRepository.findById(order.getCustomerId())
                    .map(Customer::getPhone)
                    .filter(this::hasText)
                    .orElse(null);
        }

        Context context = new Context(Locale.forLanguageTag("en-LK"));
        context.setVariable("settings", settings);
        context.setVariable("storeName", storeName);
        context.setVariable("cashierName", cashierName);
        context.setVariable("customerName", hasText(order.getCustomerName()) ? order.getCustomerName() : DEFAULT_CUSTOMER_NAME);
        context.setVariable("customerPhone", customerPhone);
        context.setVariable("issuedAt", order.getCreatedAt() != null ? DATE_TIME_FORMATTER.format(order.getCreatedAt()) : "");
        context.setVariable("paymentLabel", order.getOrderType() != null ? order.getOrderType().name() : "");
        context.setVariable("saleModeLabel", order.getSaleMode() != null ? order.getSaleMode().name().replace('_', ' ') : "");
        context.setVariable("statusLabel", order.getOrderType() != null && order.getOrderType().name().equals("CREDIT") ? "Credit Sale" : "Paid");
        context.setVariable("subTotalFormatted", formatCurrency(order.getSubTotal()));
        context.setVariable("discountFormatted", formatCurrency(order.getBillDiscount()));
        context.setVariable("grandTotalFormatted", formatCurrency(order.getGrandTotal()));
        context.setVariable("paidAmountFormatted", formatCurrency(order.getPaidAmount()));
        context.setVariable("balanceFormatted", formatCurrency(Math.max(0.0, order.getPaidAmount() - order.getGrandTotal())));
        context.setVariable("hasNotes", hasText(order.getNote()));
        context.setVariable("notesText", hasText(order.getNote())
                ? order.getNote()
                : "Thank you for your purchase. Please keep this invoice for your records.");
        context.setVariable("items", mapItems(order.getItems()));
        context.setVariable("order", order);

        String html = templateEngine.process("invoice/default", context);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to generate invoice PDF", exception);
        }
    }

    private List<InvoiceLineView> mapItems(List<OrderItemResponse> items) {
        return items.stream()
                .map(item -> new InvoiceLineView(
                        item.getItemName(),
                        item.getBarcode(),
                        formatQuantity(item.getQty(), item.getQtyUnit() != null ? item.getQtyUnit().name() : ""),
                        formatCurrency(item.getUnitPrice()),
                        formatCurrency(item.getLineTotal())
                ))
                .toList();
    }

    private String formatCurrency(double amount) {
        return CURRENCY_FORMAT.format(amount);
    }

    private String formatQuantity(BigDecimal quantity, String unit) {
        if (quantity == null) {
            return "";
        }

        BigDecimal normalized = quantity.stripTrailingZeros();
        String qtyValue = normalized.toPlainString();

        if ("KG".equalsIgnoreCase(unit) && quantity.compareTo(BigDecimal.ONE) < 0) {
            BigDecimal grams = quantity.multiply(BigDecimal.valueOf(1000)).stripTrailingZeros();
            return grams.toPlainString() + " G";
        }

        if ("G".equalsIgnoreCase(unit) && quantity.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            BigDecimal kilograms = quantity.divide(BigDecimal.valueOf(1000)).stripTrailingZeros();
            return kilograms.toPlainString() + " KG";
        }

        return hasText(unit) ? qtyValue + " " + unit : qtyValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record InvoiceLineView(
            String itemName,
            String barcode,
            String quantity,
            String unitPrice,
            String lineTotal
    ) {
    }
}
