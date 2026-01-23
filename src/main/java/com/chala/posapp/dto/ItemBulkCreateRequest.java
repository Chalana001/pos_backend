package com.chala.posapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class ItemBulkCreateRequest {

    @Valid
    @NotEmpty
    private List<ItemCreateWithStocksRequest> items;

    public List<ItemCreateWithStocksRequest> getItems() {
        return items;
    }

    public void setItems(List<ItemCreateWithStocksRequest> items) {
        this.items = items;
    }
}
