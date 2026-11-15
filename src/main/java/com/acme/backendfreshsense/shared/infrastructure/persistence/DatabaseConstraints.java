package com.acme.backendfreshsense.shared.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;

public final class DatabaseConstraints {
    private DatabaseConstraints() {}

    public static boolean matches(Throwable error, String name) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint != null && constraint.substring(constraint.lastIndexOf('.') + 1).equalsIgnoreCase(name)) {
                    return true;
                }
                SQLException sql = violation.getSQLException();
                // Hibernate no siempre extrae el nombre de la FK en los errores 1451 y 1452 de MySQL.
                return (sql.getErrorCode() == 1451 || sql.getErrorCode() == 1452)
                        && sql.getMessage().contains("CONSTRAINT `" + name + "` FOREIGN KEY");
            }
        }
        return false;
    }
}
