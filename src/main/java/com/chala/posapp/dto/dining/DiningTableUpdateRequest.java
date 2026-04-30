package com.chala.posapp.dto.dining;

import com.chala.posapp.entity.DiningTableStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DiningTableUpdateRequest {

    @Size(min = 1, max = 120)
    private String tableName;

    private DiningTableStatus status;
}
