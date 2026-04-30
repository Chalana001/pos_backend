package com.chala.posapp.dto.dining;

import com.chala.posapp.entity.DiningTableStatus;
import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DiningTableResponse {
    private Long id;
    private Long branchId;
    private String tableName;
    private DiningTableStatus status;
}
