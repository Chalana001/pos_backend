package com.chala.posapp.rbac;

public class TenantOwnershipException extends RuntimeException {
    public TenantOwnershipException(String message) {
        super(message);
    }
}
