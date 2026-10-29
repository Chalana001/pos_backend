package com.chala.posapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One main category with its sub-categories and the number of items linked to each.
 * The management screen uses this to badge rows and to block deletes that are still in use.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class CategoryUsageDto {

    private Long id;
    private String name;
    private long itemCount;
    private List<SubCategoryUsageDto> subCategories;

    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @Data
    public static class SubCategoryUsageDto {
        private Long id;
        private String name;
        private long itemCount;
    }
}
