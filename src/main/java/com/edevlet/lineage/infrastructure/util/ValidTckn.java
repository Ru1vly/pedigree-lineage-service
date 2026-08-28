package com.edevlet.lineage.infrastructure.util;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = TcknConstraintValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTckn {
    String message() default "Invalid Turkish National Identity Number (TCKN) checksum";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
