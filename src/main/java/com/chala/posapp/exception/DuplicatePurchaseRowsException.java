package com.chala.posapp.exception;

import java.util.List;

public class DuplicatePurchaseRowsException extends RuntimeException {
    private final List<Duplicate> duplicates;

    public DuplicatePurchaseRowsException(List<Duplicate> duplicates) {
        super("Duplicate purchase entries detected.");
        this.duplicates = List.copyOf(duplicates);
    }

    public List<Duplicate> getDuplicates() {
        return duplicates;
    }

    public record Duplicate(int rowIndex, int duplicateOfRowIndex, Long itemId) {}
}
