package com.chala.posapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerNoteUpdateRequest {
    @NotBlank
    @Size(max = 2000)
    private String note;
}
