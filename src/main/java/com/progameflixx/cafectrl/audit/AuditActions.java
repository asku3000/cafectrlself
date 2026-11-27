package com.progameflixx.cafectrl.audit;

/** Catalogue of audit action and entity-type names, so the viewer's filters and the code agree. */
public final class AuditActions {

    private AuditActions() {}

    // ---- entity types ----
    public static final String ENTITY_USER = "USER";
    public static final String ENTITY_CAFE = "CAFE";
    public static final String ENTITY_SESSION = "SESSION";          // a customer session; its bill is the same id
    public static final String ENTITY_RESOURCE = "RESOURCE";
    public static final String ENTITY_RATE_CARD = "RATE_CARD";
    public static final String ENTITY_GAME_TYPE = "GAME_TYPE";
    public static final String ENTITY_ACCESSORY = "ACCESSORY";
    public static final String ENTITY_INVENTORY = "INVENTORY_ITEM";
    public static final String ENTITY_PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String ENTITY_MEMBERSHIP_PLAN = "MEMBERSHIP_PLAN";
    public static final String ENTITY_MEMBERSHIP_CARD = "MEMBERSHIP_CARD";
    public static final String ENTITY_MEMBERSHIP_SETTINGS = "MEMBERSHIP_SETTINGS";
    public static final String ENTITY_AUDIT_LOG = "AUDIT_LOG";

    // ---- auth ----
    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SIGNUP = "SIGNUP";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";

    // ---- security ----
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    // ---- setup ----
    public static final String CAFE_UPDATED = "CAFE_UPDATED";
    public static final String RESOURCE_CREATED = "RESOURCE_CREATED";
    public static final String RESOURCE_UPDATED = "RESOURCE_UPDATED";
    public static final String RESOURCE_DELETED = "RESOURCE_DELETED";
    public static final String RATE_CARD_CREATED = "RATE_CARD_CREATED";
    public static final String RATE_CARD_UPDATED = "RATE_CARD_UPDATED";
    public static final String RATE_CARD_DELETED = "RATE_CARD_DELETED";
    public static final String GAME_TYPE_CREATED = "GAME_TYPE_CREATED";
    public static final String GAME_TYPE_DELETED = "GAME_TYPE_DELETED";
    public static final String ACCESSORY_CREATED = "ACCESSORY_CREATED";
    public static final String ACCESSORY_UPDATED = "ACCESSORY_UPDATED";
    public static final String ACCESSORY_DELETED = "ACCESSORY_DELETED";
    public static final String INVENTORY_ITEM_CREATED = "INVENTORY_ITEM_CREATED";
    public static final String INVENTORY_ITEM_UPDATED = "INVENTORY_ITEM_UPDATED";
    public static final String INVENTORY_ITEM_DELETED = "INVENTORY_ITEM_DELETED";

    // ---- staff ----
    public static final String STAFF_CREATED = "STAFF_CREATED";
    public static final String STAFF_UPDATED = "STAFF_UPDATED";
    public static final String STAFF_DEACTIVATED = "STAFF_DEACTIVATED";
    public static final String STAFF_REACTIVATED = "STAFF_REACTIVATED";
    public static final String STAFF_DELETED = "STAFF_DELETED";

    // ---- sessions ----
    public static final String SESSION_STARTED = "SESSION_STARTED";
    public static final String SESSION_GAME_ADDED = "SESSION_GAME_ADDED";
    public static final String SESSION_GAME_ENDED = "SESSION_GAME_ENDED";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";
    public static final String SESSION_ITEM_ADDED = "SESSION_ITEM_ADDED";
    public static final String SESSION_ITEM_REMOVED = "SESSION_ITEM_REMOVED";
    public static final String SESSION_CARD_TAGGED = "SESSION_CARD_TAGGED";
    public static final String SESSION_CARD_CHANGED = "SESSION_CARD_CHANGED";
    public static final String SESSION_CARD_REMOVED = "SESSION_CARD_REMOVED";

    // ---- billing (written in the same transaction as the change) ----
    public static final String BILL_CREATED = "BILL_CREATED";
    /** A reopened bill closed again with new values. */
    public static final String BILL_EDITED = "BILL_EDITED";
    public static final String BILL_DISCOUNT_APPLIED = "BILL_DISCOUNT_APPLIED";
    /** Positive manual adjustment (surcharge). */
    public static final String BILL_ADJUSTED = "BILL_ADJUSTED";
    public static final String PAYMENT_RECORDED = "PAYMENT_RECORDED";
    public static final String BILL_REOPENED = "BILL_REOPENED";
    public static final String BILL_VOIDED = "BILL_VOIDED";
    public static final String PENDING_PAYMENT_SETTLED = "PENDING_PAYMENT_SETTLED";

    // ---- memberships ----
    public static final String MEMBERSHIP_PLAN_CREATED = "MEMBERSHIP_PLAN_CREATED";
    public static final String MEMBERSHIP_PLAN_UPDATED = "MEMBERSHIP_PLAN_UPDATED";
    public static final String MEMBERSHIP_PLAN_ACTIVATED = "MEMBERSHIP_PLAN_ACTIVATED";
    public static final String MEMBERSHIP_PLAN_DEACTIVATED = "MEMBERSHIP_PLAN_DEACTIVATED";
    public static final String MEMBERSHIP_SETTINGS_UPDATED = "MEMBERSHIP_SETTINGS_UPDATED";
    // card actions (same transaction as the balance change)
    public static final String CARD_ISSUED = "CARD_ISSUED";
    public static final String CARD_RECHARGED = "CARD_RECHARGED";
    public static final String CARD_ADJUSTED = "CARD_ADJUSTED";
    public static final String CARD_EXPIRY_EXTENDED = "CARD_EXPIRY_EXTENDED";
    public static final String CARD_START_DATE_CHANGED = "CARD_START_DATE_CHANGED";
    public static final String CARD_SUSPENDED = "CARD_SUSPENDED";
    public static final String CARD_REACTIVATED = "CARD_REACTIVATED";
    public static final String CARD_CLOSED = "CARD_CLOSED";
    public static final String CARD_EXPIRED = "CARD_EXPIRED";
    public static final String CARD_DEBITED = "CARD_DEBITED";
    public static final String CARD_DEBIT_REVERSED = "CARD_DEBIT_REVERSED";

    // ---- legacy / generic ----
    /** Used by the old AuditService.log(...) bridge until every caller is moved to a specific action. */
    public static final String LEGACY = "LEGACY";

    // ---- system ----
    public static final String AUDIT_EXPORTED = "AUDIT_EXPORTED";
    public static final String AUDIT_PURGE_RUN = "AUDIT_PURGE_RUN";
    public static final String AUDIT_PURGE_DRY_RUN = "AUDIT_PURGE_DRY_RUN";
}
