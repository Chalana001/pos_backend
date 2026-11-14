package com.chala.posapp.rbac.shadow;

public final class ShadowRequestAttributes {
    public static final String OUTCOME_CLASS = ShadowRequestAttributes.class.getName() + ".outcomeClass";
    public static final String OUTCOME_REASON = ShadowRequestAttributes.class.getName() + ".outcomeReason";
    public static final String AUTHENTICATION_ATTEMPTED = ShadowRequestAttributes.class.getName() + ".authenticationAttempted";
    public static final String AUTHENTICATED_USER_ID = ShadowRequestAttributes.class.getName() + ".authenticatedUserId";
    public static final String CORRELATION_ID = ShadowRequestAttributes.class.getName() + ".correlationId";
    public static final String EVENT_SEQUENCE = ShadowRequestAttributes.class.getName() + ".eventSequence";

    private ShadowRequestAttributes() {
    }
}
