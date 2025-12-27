package br.edu.ifba.document;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.inject.Qualifier;

/**
 * Qualifier to mark the Hibernate/PostgreSQL implementation of
 * DocumentRepositoryPort.
 * Used to disambiguate injection when both the concrete implementation and
 * producer
 * method provide DocumentRepositoryPort.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
public @interface HibernateDocument {
}
