package com.progameflixx.cafectrl.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a critical controller action (money, sessions, bills, card balances, deletes) as idempotent.
 * The request must carry an {@code Idempotency-Key} header (one key per user action). The first request with a
 * key runs normally, and its response is stored in the same transaction as the action; any later request with the
 * same key returns that stored response without running the action again (no second ledger / audit entry).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** Action name stored with the key, e.g. "SESSION_CHECKOUT". */
    String value();

    /** When true (default), a request without the header is refused with 400. */
    boolean required() default true;
}
