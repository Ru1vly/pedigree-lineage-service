package com.edevlet.lineage.infrastructure.util;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TcknConstraintValidator implements ConstraintValidator<ValidTckn, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return TcknValidator.isValid(value);
    }
}
