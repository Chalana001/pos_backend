package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodRecipeDineInIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void recipeItemsSupportPendingDineInFlowAndIngredientStockRollback() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("food"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category groceryCategory = new Category();
        groceryCategory.setTenantId(tenantId);
        groceryCategory.setName("Groceries");
        groceryCategory = categoryRepository.save(groceryCategory);

        SubCategory grocerySubCategory = new SubCategory();
        grocerySubCategory.setTenantId(tenantId);
        grocerySubCategory.setName("Kitchen Stock");
        grocerySubCategory.setCategory(groceryCategory);
        grocerySubCategory = subCategoryRepository.save(grocerySubCategory);

        Category foodCategory = new Category();
        foodCategory.setTenantId(tenantId);
        foodCategory.setName("Foods");
        foodCategory = categoryRepository.save(foodCategory);

        SubCategory foodSubCategory = new SubCategory();
        foodSubCategory.setTenantId(tenantId);
        foodSubCategory.setName("Short Eats");
        foodSubCategory.setCategory(foodCategory);
        foodSubCategory = subCategoryRepository.save(foodSubCategory);

        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        supplier.setName("Kitchen Supplier");
        supplier.setPhone("0712345678");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode eggItem = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "93001",
                  "name": "Egg",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 5
                }
                """.formatted(grocerySubCategory.getId())
        );
        long eggItemId = eggItem.path("id").asLong();

        JsonNode cheeseItem = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "93002",
                  "name": "Cheese",
                  "subCategoryId": %d,
                  "costPrice": 1000,
                  "sellingPrice": 1400,
                  "reorderLevel": 0.5,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG"
                }
                """.formatted(grocerySubCategory.getId())
        );
        long cheeseItemId = cheeseItem.path("id").asLong();

        JsonNode purchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "FOOD-GRN-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 20,
                          "costPrice": 20,
                          "sellingPrice": 30
                        },
                        {
                          "itemId": %d,
                          "qty": 2,
                          "costPrice": 1000,
                          "sellingPrice": 1400
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), eggItemId, cheeseItemId)
        );
        assertEquals(2400.0, purchase.path("grandTotal").asDouble(), 0.001);

        JsonNode recipeItem = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "94001",
                  "name": "Cheese Omelette",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 180,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "isKotEnabled": true,
                  "ingredients": [
                    {
                      "ingredientItemId": %d,
                      "quantity": 2
                    },
                    {
                      "ingredientItemId": %d,
                      "quantity": 100,
                      "qtyUnit": "G"
                    }
                  ]
                }
                """.formatted(foodSubCategory.getId(), eggItemId, cheeseItemId)
        );
        long recipeItemId = recipeItem.path("id").asLong();
        assertEquals("RECIPE", recipeItem.path("itemType").asText());
        assertTrue(recipeItem.path("isKotEnabled").asBoolean());
        assertEquals(2, recipeItem.path("ingredients").size());

        JsonNode updatedRecipe = putJson(
                "/items/" + recipeItemId,
                tenantId,
                adminToken,
                """
                {
                  "name": "Cheese Omelette Deluxe",
                  "sellingPrice": 220,
                  "itemType": "RECIPE",
                  "isKotEnabled": true,
                  "ingredients": [
                    {
                      "ingredientItemId": %d,
                      "quantity": 2
                    },
                    {
                      "ingredientItemId": %d,
                      "quantity": 100,
                      "qtyUnit": "G"
                    }
                  ]
                }
                """.formatted(eggItemId, cheeseItemId)
        );
        assertEquals("Cheese Omelette Deluxe", updatedRecipe.path("name").asText());
        assertEquals(220.0, updatedRecipe.path("sellingPrice").asDouble(), 0.001);
        assertEquals(2, updatedRecipe.path("ingredients").size());

        JsonNode posItems = getJson(
                "/items/searchForPos?name=Omelette&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                cashierToken
        );
        assertEquals(1, posItems.size());
        assertEquals(recipeItemId, posItems.get(0).path("id").asLong());

        JsonNode table = postJson(
                "/dining-tables",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "tableName": "T1"
                }
                """.formatted(fixture.mainBranch().getId())
        );
        long tableId = table.path("id").asLong();
        assertEquals("AVAILABLE", table.path("status").asText());

        JsonNode pendingOrder = putJson(
                "/pending-orders/table/" + tableId,
                tenantId,
                cashierToken,
                """
                {
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 180,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "note": "Hold dine-in order"
                }
                """.formatted(recipeItemId)
        );
        assertEquals("DINE_IN", pendingOrder.path("saleMode").asText());
        assertEquals("T1", pendingOrder.path("tableName").asText());
        assertEquals(180.0, pendingOrder.path("grandTotal").asDouble(), 0.001);

        JsonNode occupiedTable = getJson("/dining-tables/" + tableId, tenantId, cashierToken);
        assertEquals("OCCUPIED", occupiedTable.path("status").asText());

        JsonNode recalledPending = getJson("/pending-orders/table/" + tableId, tenantId, cashierToken);
        assertEquals(1, recalledPending.path("items").size());
        assertEquals(recipeItemId, recalledPending.path("items").get(0).path("itemId").asLong());

        JsonNode pendingList = getJson("/pending-orders?branchId=" + fixture.mainBranch().getId(), tenantId, cashierToken);
        assertEquals(1, pendingList.size());

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 2000,
                  "note": "Food shift"
                }
                """
        );

        JsonNode order = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "saleMode": "DINE_IN",
                  "tableId": %d,
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 2,
                      "unitPrice": 180,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 360,
                  "note": "Checkout dine-in order"
                }
                """.formatted(fixture.mainBranch().getId(), tableId, recipeItemId)
        );
        String invoiceNo = order.path("invoiceNo").asText();
        assertEquals("DINE_IN", order.path("saleMode").asText());
        assertEquals(tableId, order.path("tableId").asLong());
        assertEquals("T1", order.path("tableName").asText());
        assertEquals(360.0, order.path("grandTotal").asDouble(), 0.001);

        JsonNode availableTable = getJson("/dining-tables/" + tableId, tenantId, cashierToken);
        assertEquals("AVAILABLE", availableTable.path("status").asText());

        JsonNode clearedPending = getJson("/pending-orders?branchId=" + fixture.mainBranch().getId(), tenantId, cashierToken);
        assertEquals(0, clearedPending.size());
        jsonRequest(org.springframework.http.HttpMethod.GET, "/pending-orders/table/" + tableId, tenantId, cashierToken, null, 404);

        String today = LocalDate.now().toString();
        JsonNode recipeTopSelling = getJson(
                "/reports/top-selling?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&itemType=RECIPE&limit=10",
                tenantId,
                adminToken
        );
        assertEquals(1, recipeTopSelling.size());
        assertEquals(recipeItemId, recipeTopSelling.get(0).path("itemId").asLong());
        assertEquals("RECIPE", recipeTopSelling.get(0).path("itemType").asText());

        JsonNode normalTopSelling = getJson(
                "/reports/top-selling?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&itemType=NORMAL&limit=10",
                tenantId,
                adminToken
        );
        assertTrue(normalTopSelling.isEmpty());

        JsonNode recipeProfit = getJson(
                "/reports/profit?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&itemType=RECIPE&limit=10",
                tenantId,
                adminToken
        );
        assertEquals(1, recipeProfit.size());
        assertEquals("RECIPE", recipeProfit.get(0).path("itemType").asText());

        assertEquals(16, firstBatchFor(fixture.mainBranch().getId(), eggItemId).getQuantity());
        assertEquals(1800, firstBatchFor(fixture.mainBranch().getId(), cheeseItemId).getQuantity());

        JsonNode canceledOrder = postJson(
                "/orders/" + invoiceNo + "/cancel",
                tenantId,
                adminToken,
                """
                {
                  "reason": "Customer cancelled meal"
                }
                """
        );
        assertEquals("CANCELED", canceledOrder.path("status").asText());
        assertEquals(20, firstBatchFor(fixture.mainBranch().getId(), eggItemId).getQuantity());
        assertEquals(2000, firstBatchFor(fixture.mainBranch().getId(), cheeseItemId).getQuantity());
    }
}
