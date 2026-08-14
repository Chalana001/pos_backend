package com.chala.posapp.integration;

import com.chala.posapp.entity.*;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.supplier.SupplierItem;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.repository.SupplierItemReadRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ReorderPlanIntegrationTest extends ApiIntegrationTestSupport {
    @Autowired SupplierItemReadRepository supplierItemReadRepository;
    @Autowired RecipeIngredientRepository recipeIngredientRepository;

    @Test
    void fullPlanLifecycleDoesNotMutatePurchasesGrnsOrStock() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("reorder-plan"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        Item item = saveForecastItem(fixture, "PLAN-ITEM");
        Supplier supplier = saveSupplier("PLAN-SUPPLIER");
        SupplierItem mapping = new SupplierItem(); mapping.setItem(item); mapping.setSupplier(supplier);
        mapping.setPrimarySupplier(true); mapping.setLastBuyingPrice(7.5); supplierItemReadRepository.save(mapping);
        for (int day = 1; day <= 20; day++) saveForecastSale(fixture, item, day);

        long purchasesBefore = purchaseRepository.count(); long grnsBefore = grnRepository.count();
        int stockBefore = firstBatchFor(fixture.mainBranch().getId(), item.getId()).getQuantity();

        var plan = postJson("/reorder-plans", tenantId, token, """
                {"name":"Weekly plan","branchId":%d,"forecastDays":30,"targetCoverDays":30}
                """.formatted(fixture.mainBranch().getId()));
        assertEquals("DRAFT", plan.path("status").asText());
        assertTrue(plan.path("lines").size() > 0);
        plan = postJson("/reorder-plans/" + plan.path("id").asLong() + "/submit", tenantId, token, "{}");
        assertEquals("PENDING_APPROVAL", plan.path("status").asText());
        plan = postJson("/reorder-plans/" + plan.path("id").asLong() + "/approve", tenantId, token, "{}");
        assertEquals("APPROVED", plan.path("status").asText());
        var drafts = getJson("/reorder-plans/" + plan.path("id").asLong() + "/purchase-drafts", tenantId, token);
        assertEquals(1, drafts.size()); assertEquals(supplier.getId().longValue(), drafts.get(0).path("supplierId").asLong());
        plan = postJson("/reorder-plans/" + plan.path("id").asLong() + "/mark-handoff-complete", tenantId, token, "{\"reference\":\"TEST-HANDOFF-1\"}");
        assertEquals("CONVERTED", plan.path("status").asText());

        assertEquals(purchasesBefore, purchaseRepository.count()); assertEquals(grnsBefore, grnRepository.count());
        assertEquals(stockBefore, firstBatchFor(fixture.mainBranch().getId(), item.getId()).getQuantity());
    }

    @Test
    void recipeAndServiceDemandProducesOnlyIntegerIngredientLines() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("recipe-plan"), 1);
        String tenantId = fixture.tenantId(); String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        Item ingredient = saveForecastItem(fixture, "SHARED-INGREDIENT");
        Item recipeA = saveNonStockItem(fixture, "RECIPE-A", ItemType.RECIPE);
        Item recipeB = saveNonStockItem(fixture, "RECIPE-B", ItemType.RECIPE);
        Item service = saveNonStockItem(fixture, "SERVICE-A", ItemType.SERVICE);
        recipeIngredientRepository.save(RecipeIngredient.builder().parentItemId(recipeA.getId()).ingredientId(ingredient.getId()).quantity(250).build());
        recipeIngredientRepository.save(RecipeIngredient.builder().parentItemId(recipeB.getId()).ingredientId(ingredient.getId()).quantity(100).build());
        for (int day=1;day<=20;day++){saveForecastSale(fixture,ingredient,day);saveForecastSale(fixture,recipeA,day);saveForecastSale(fixture,recipeB,day);saveForecastSale(fixture,service,day);}

        var plan=postJson("/reorder-plans",tenantId,token,"""
                {"name":"Recipe plan","branchId":%d,"forecastDays":30,"targetCoverDays":30}
                """.formatted(fixture.mainBranch().getId()));
        assertEquals(1,plan.path("lines").size());
        var line=plan.path("lines").get(0);
        assertEquals(ingredient.getId().longValue(),line.path("itemId").asLong());
        assertTrue(line.path("directDemandBase").asLong()>0);
        assertTrue(line.path("recipeDemandBase").asLong()>0);
        assertEquals(line.path("directDemandBase").asLong()+line.path("recipeDemandBase").asLong(),line.path("totalDemandBase").asLong());
        assertEquals(line.path("totalDemandBase").asLong()-1000,line.path("suggestedQtyBase").asLong());
        assertEquals(0, line.path("suggestedQtyBase").asLong() % 1);
    }

    private Item saveForecastItem(TenantFixture fixture, String barcode) {
        Category c = new Category(); c.setName(barcode); c = categoryRepository.save(c);
        SubCategory sc = new SubCategory(); sc.setName(barcode); sc.setCategory(c); sc = subCategoryRepository.save(sc);
        Item item = itemRepository.save(Item.builder().barcode(barcode).name(barcode).subCategory(sc).costPrice(BigDecimal.valueOf(6))
                .sellingPrice(BigDecimal.valueOf(12)).reorderLevel(3000).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS).active(true).build());
        stockBatchRepository.save(StockBatch.builder().branch(fixture.mainBranch()).item(item).batchCode(barcode).sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(6)).sellingPrice(BigDecimal.valueOf(12)).quantity(1000).originalQuantity(1000).build()); return item;
    }
    private Supplier saveSupplier(String name){Supplier s=new Supplier();s.setName(name);s.setPhone("07"+System.nanoTime()%1_000_000_000L);s.setActive(true);return supplierRepository.save(s);}
    private Item saveNonStockItem(TenantFixture fixture,String barcode,ItemType type){Category c=new Category();c.setName(barcode);c=categoryRepository.save(c);SubCategory sc=new SubCategory();sc.setName(barcode);sc.setCategory(c);sc=subCategoryRepository.save(sc);return itemRepository.save(Item.builder().barcode(barcode).name(barcode).subCategory(sc).costPrice(BigDecimal.ZERO).sellingPrice(BigDecimal.valueOf(20)).reorderLevel(0).itemType(type).defaultUnit(type==ItemType.SERVICE?MeasurementUnit.SERVICE:MeasurementUnit.PCS).active(true).posVisible(true).build());}
    private void saveForecastSale(TenantFixture fixture,Item item,int day){Order o=orderRepository.save(Order.builder().invoiceNo("PLAN-"+item.getId()+"-"+day).branchId(fixture.mainBranch().getId()).cashierUserId(fixture.admin().getId()).orderType(OrderType.CASH).paymentMethod("CASH").saleMode(SaleMode.TAKEAWAY).status(OrderStatus.COMPLETED).subTotal(24.0).grandTotal(24.0).paidAmount(24.0).dueAmount(0.0).salePaidAmount(24.0).saleDueAmount(0.0).createdAt(LocalDateTime.now().minusDays(day)).build());orderItemRepository.save(OrderItem.builder().orderId(o.getId()).itemId(item.getId()).barcode(item.getBarcode()).itemName(item.getName()).itemType(item.getItemType()).qty(item.getItemType()==ItemType.NORMAL?2000:2).displayQty(BigDecimal.valueOf(2)).qtyUnit(item.getItemType()==ItemType.SERVICE?MeasurementUnit.SERVICE:MeasurementUnit.PCS).costPrice(6.0).unitPrice(12.0).discountType(DiscountType.NONE).discountValue(0.0).finalUnitPrice(12.0).lineCost(12.0).lineTotal(24.0).build());}
}
