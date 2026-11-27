package com.progameflixx.cafectrl.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on a controller write method so that a rejected or failed request (an exception, or a 4xx/5xx response)
 * is recorded as a FAILURE audit entry automatically. Successful outcomes are recorded explicitly by the code
 * that knows the details (before/after values, amounts), so they are not duplicated here.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Action name recorded on failure, e.g. {@link AuditActions#BILL_CREATED}. */
    String action();

    /** Entity type, e.g. {@link AuditActions#ENTITY_SESSION}. */
    String entityType() default "";

    /** Name of the method parameter holding the entity id (usually the path variable), if any. */
    String entityIdParam() default "";
}
