package com.chala.posapp.dto.promotion;

import lombok.Data;

/** A note to go with approve / reject / pause, shown in the audit trail. */
@Data
public class LifecycleActionRequest {
    private String note;
}
