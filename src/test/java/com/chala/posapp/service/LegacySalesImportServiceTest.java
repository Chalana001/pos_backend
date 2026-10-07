package com.chala.posapp.service;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.OrderPayment;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.OrderItemRepository;
import com.chala.posapp.repository.OrderPaymentRepository;
import com.chala.posapp.repository.OrderRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Legacy imports arrive after V59 has already run, so the import path itself has to write
 * the initial-payment row. Without this, a completed imported sale is invisible to the
 * one-row-per-completed-sale ledger.
 */
class LegacySalesImportServiceTest {

    private static final LocalDateTime SOLD_AT = LocalDateTime.of(2025, 10, 1, 14, 30);

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private ItemRepository itemRepository;
    private BranchRepository branchRepository;
    private UserRepository userRepository;
    private OrderPaymentRepository orderPaymentRepository;
    private SecurityUtils securityUtils;
    private LegacySalesImportService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        itemRepository = mock(ItemRepository.class);
        branchRepository = mock(BranchRepository.class);
        userRepository = mock(UserRepository.class);
        orderPaymentRepository = mock(OrderPaymentRepository.class);
        securityUtils = mock(SecurityUtils.class);
        service = new LegacySalesImportService(
                orderRepository,
                orderItemRepository,
                itemRepository,
                branchRepository,
                userRepository,
                orderPaymentRepository,
                securityUtils
        );

        Branch branch = Branch.builder()
                .id(10L)
                .code("MAIN")
                .name("Main Branch")
                .address("Colombo")
                .phone("0110000000")
                .active(true)
                .build();
        User admin = User.builder()
                .id(1L)
                .username("admin")
                .passwordHash("x")
                .role(Role.ADMIN)
                .enabled(true)
                .build();
        User cashier = User.builder()
                .id(2L)
                .username("legacy-cashier")
                .passwordHash("x")
                .role(Role.CASHIER)
                .enabled(true)
                .branchId(10L)
                .build();
        Item item = Item.builder()
                .id(20L)
                .barcode("ITEM-20")
                .name("Legacy Item")
                .costPrice(BigDecimal.valueOf(2.00))
                .sellingPrice(BigDecimal.valueOf(3.00))
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(securityUtils.isAdminLike(admin)).thenReturn(true);
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch));
        when(userRepository.findById(2L)).thenReturn(Optional.of(cashier));
        when(itemRepository.findAll()).thenReturn(List.of(item));
        when(orderRepository.findByInvoiceNo("LEGACY-1001")).thenReturn(Optional.empty());
        when(orderRepository.findByClientSaleId("legacy:legacy-1001")).thenReturn(Optional.empty());

        AtomicLong orderId = new AtomicLong(99L);
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> {
            Order order = call.getArgument(0);
            order.setId(orderId.incrementAndGet());
            return order;
        });
        when(orderItemRepository.saveAll(anyList())).thenAnswer(call -> call.<List<OrderItem>>getArgument(0));
        when(orderPaymentRepository.save(any(OrderPayment.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("an imported completed sale writes exactly one initial payment row")
    void commitWritesOneInitialPaymentPerImportedSale() {
        String csv = """
                sn,sale_no,internal_sale_id,order_type,date_time,customer,total_payable,payment_method,added_by,item_name,qty,unit_price,line_total
                1,LEGACY-1001,legacy-1001,Takeaway,01/10/2025 14:30,Walk-in,12.00,CASH,Old cashier,Legacy Item,4,3.00,12.00
                """;
        MockMultipartFile salesFile = new MockMultipartFile(
                "salesFile", "sales.csv", "text/csv", csv.getBytes()
        );

        var response = service.commit(salesFile, null, 10L, 2L);

        assertThat(response.getImportedSales()).isEqualTo(1);
        assertThat(response.getImportedItems()).isEqualTo(1);
        assertThat(response.getInvoiceNos()).containsExactly("LEGACY-1001");

        ArgumentCaptor<OrderPayment> payments = ArgumentCaptor.forClass(OrderPayment.class);
        verify(orderPaymentRepository, times(1)).save(payments.capture());
        OrderPayment payment = payments.getValue();

        assertThat(payment.getOrderId()).isEqualTo(100L);
        assertThat(payment.getInvoiceNo()).isEqualTo("LEGACY-1001");
        assertThat(payment.getBranchId()).isEqualTo(10L);
        assertThat(payment.getCashierUserId()).isEqualTo(2L);
        assertThat(payment.getAmount()).isEqualTo(12.0);
        assertThat(payment.getPaymentMethod()).isEqualTo("CASH");
        assertThat(payment.getShortfallAmount()).isNull();
        assertThat(payment.getTenderedAmount()).isNull();
        assertThat(payment.getChangeAmount()).isNull();
        assertThat(payment.getBankReference()).isNull();
        assertThat(payment.getPaidAt()).isEqualTo(SOLD_AT);
        assertThat(payment.isLegacyBackfilled()).isTrue();
        assertThat(payment.getNote()).isEqualTo("Legacy import initial payment");

        verify(orderRepository, times(1)).save(any(Order.class));
        ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.captor();
        verify(orderItemRepository, times(1)).saveAll(items.capture());
        assertThat(items.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("a partially paid imported sale records the initial shortfall without inventing a tender")
    void commitRecordsShortfallWithoutInventedTender() {
        String csv = """
                sn,sale_no,internal_sale_id,order_type,date_time,customer,total_payable,payment_method,added_by,item_name,qty,unit_price,line_total
                1,LEGACY-1001,legacy-1001,Takeaway,01/10/2025 14:30,Walk-in,12.00,CASH:5,Old cashier,Legacy Item,4,3.00,12.00
                """;
        MockMultipartFile salesFile = new MockMultipartFile(
                "salesFile", "sales.csv", "text/csv", csv.getBytes()
        );

        service.commit(salesFile, null, 10L, 2L);

        ArgumentCaptor<OrderPayment> payments = ArgumentCaptor.forClass(OrderPayment.class);
        verify(orderPaymentRepository, times(1)).save(payments.capture());

        assertThat(payments.getValue().getAmount()).isEqualTo(5.0);
        assertThat(payments.getValue().getShortfallAmount()).isEqualTo(7.0);
        assertThat(payments.getValue().getTenderedAmount()).isNull();
        assertThat(payments.getValue().getChangeAmount()).isNull();
        assertThat(payments.getValue().isLegacyBackfilled()).isTrue();
    }

    @Test
    @DisplayName("an unsupported legacy method is stored as LEGACY so the ledger stays auditable")
    void commitStoresUnsupportedMethodAsLegacy() {
        String csv = """
                sn,sale_no,internal_sale_id,order_type,date_time,customer,total_payable,payment_method,added_by,item_name,qty,unit_price,line_total
                1,LEGACY-1001,legacy-1001,Takeaway,01/10/2025 14:30,Walk-in,12.00,CHEQUE,Old cashier,Legacy Item,4,3.00,12.00
                """;
        MockMultipartFile salesFile = new MockMultipartFile(
                "salesFile", "sales.csv", "text/csv", csv.getBytes()
        );

        service.commit(salesFile, null, 10L, 2L);

        ArgumentCaptor<OrderPayment> payments = ArgumentCaptor.forClass(OrderPayment.class);
        verify(orderPaymentRepository, times(1)).save(payments.capture());
        assertThat(payments.getValue().getPaymentMethod()).isEqualTo("LEGACY");
    }
}
