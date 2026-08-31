package com.chala.posapp.dto.item;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ItemDeleteCheckResponse {
    private Long itemId;
    private String itemName;
    private boolean canDelete;
    private List<String> reasons;
}
