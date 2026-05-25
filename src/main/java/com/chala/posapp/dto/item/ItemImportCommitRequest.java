package com.chala.posapp.dto.item;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ItemImportCommitRequest {
    @NotEmpty(message = "Rows cannot be empty")
    private List<@Valid ItemImportRowData> rows;
}
