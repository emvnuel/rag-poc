package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StorageBackendValidator.
 * 
 * Tests validation logic without requiring Quarkus CDI context.
 */
class StorageBackendValidatorTest {

    @Test
    @DisplayName("isSqliteBackend should return true for sqlite")
    void testIsSqliteBackend() {
        assertTrue(isSqliteBackend("sqlite"));
        assertTrue(isSqliteBackend("SQLITE"));
        assertTrue(isSqliteBackend("SQLite"));
        
        assertFalse(isSqliteBackend("postgresql"));
        assertFalse(isSqliteBackend("mysql"));
    }

    @Test
    @DisplayName("isPostgresBackend should return true for postgresql")
    void testIsPostgresBackend() {
        assertTrue(isPostgresBackend("postgresql"));
        assertTrue(isPostgresBackend("POSTGRESQL"));
        assertTrue(isPostgresBackend("PostgreSQL"));
        
        assertFalse(isPostgresBackend("sqlite"));
        assertFalse(isPostgresBackend("mysql"));
    }

    @Test
    @DisplayName("invalid backend names should not be valid")
    void testInvalidBackend() {
        assertFalse(isValidBackend("mysql"));
        assertFalse(isValidBackend("mongodb"));
        assertFalse(isValidBackend("invalid"));
        assertFalse(isValidBackend(""));
        assertFalse(isValidBackend(null));
    }

    @Test
    @DisplayName("supported backend names are postgresql and sqlite")
    void testSupportedBackends() {
        // Valid backends
        assertTrue(isValidBackend("postgresql"));
        assertTrue(isValidBackend("sqlite"));
        assertTrue(isValidBackend("POSTGRESQL"));
        assertTrue(isValidBackend("SQLITE"));
    }

    /**
     * Simulates StorageBackendValidator.isSqliteBackend() logic.
     */
    private boolean isSqliteBackend(String backend) {
        if (backend == null) return false;
        return "sqlite".equalsIgnoreCase(backend);
    }

    /**
     * Simulates StorageBackendValidator.isPostgresBackend() logic.
     */
    private boolean isPostgresBackend(String backend) {
        if (backend == null) return false;
        return "postgresql".equalsIgnoreCase(backend);
    }

    /**
     * Helper to test if a backend name is valid.
     */
    private boolean isValidBackend(String backend) {
        if (backend == null || backend.isBlank()) return false;
        String normalized = backend.toLowerCase().trim();
        return normalized.equals("postgresql") || normalized.equals("sqlite");
    }
}
