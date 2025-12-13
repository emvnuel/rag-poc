package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for SQLiteSchemaMigrator.
 * 
 * Tests verify:
 * 1. Schema version tracking works correctly
 * 2. Initial migration creates all required tables
 * 3. Migrations are applied in order
 * 4. Already applied migrations are skipped
 * 5. Migration failures are handled gracefully
 */
class SQLiteSchemaMigratorTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteSchemaMigrator migrator;
    private Path dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        migrator = new SQLiteSchemaMigrator();
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Test that getCurrentVersion returns 0 for new database.
     */
    @Test
    void testGetCurrentVersionReturnsZeroForNewDatabase() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            int version = migrator.getCurrentVersion(conn);
            assertEquals(0, version, "New database should have version 0");
        }
    }

    /**
     * Test that migrateToLatest creates schema_version table.
     */
    @Test
    void testMigrateToLatestCreatesSchemaVersionTable() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            migrator.migrateToLatest(conn);
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
                assertTrue(rs.next(), "schema_version table should exist");
            }
        }
    }

    /**
     * Test that migrateToLatest creates all required tables.
     */
    @Test
    void testMigrateToLatestCreatesRequiredTables() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            migrator.migrateToLatest(conn);
            
            List<String> requiredTables = List.of(
                "schema_version",
                "projects",
                "documents",
                "vectors",
                "graph_entities",
                "graph_relations",
                "extraction_cache",
                "kv_store",
                "document_status"
            );
            
            for (String table : requiredTables) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertTrue(rs.next(), "Table " + table + " should exist");
                }
            }
        }
    }

    /**
     * Test that getCurrentVersion returns correct version after migration.
     */
    @Test
    void testGetCurrentVersionAfterMigration() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            migrator.migrateToLatest(conn);
            
            int version = migrator.getCurrentVersion(conn);
            assertTrue(version > 0, "Version should be greater than 0 after migration");
        }
    }

    /**
     * Test that migrations are idempotent.
     */
    @Test
    void testMigrationsAreIdempotent() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            // Apply migrations twice
            migrator.migrateToLatest(conn);
            int versionAfterFirst = migrator.getCurrentVersion(conn);
            
            migrator.migrateToLatest(conn);
            int versionAfterSecond = migrator.getCurrentVersion(conn);
            
            assertEquals(versionAfterFirst, versionAfterSecond, 
                "Version should be same after running migrations twice");
        }
    }

    /**
     * Test that getMigrations returns non-empty list.
     */
    @Test
    void testGetMigrationsReturnsNonEmptyList() {
        List<SQLiteSchemaMigrator.Migration> migrations = migrator.getMigrations();
        assertNotNull(migrations, "Migrations should not be null");
        assertFalse(migrations.isEmpty(), "Migrations should not be empty");
    }

    /**
     * Test that migrations have valid versions.
     */
    @Test
    void testMigrationsHaveValidVersions() {
        List<SQLiteSchemaMigrator.Migration> migrations = migrator.getMigrations();
        
        int previousVersion = 0;
        for (SQLiteSchemaMigrator.Migration migration : migrations) {
            assertTrue(migration.getVersion() > previousVersion, 
                "Migration versions should be strictly increasing");
            assertNotNull(migration.getDescription(), 
                "Migration description should not be null");
            assertFalse(migration.getDescription().isEmpty(), 
                "Migration description should not be empty");
            previousVersion = migration.getVersion();
        }
    }

    /**
     * Test that required indexes are created.
     */
    @Test
    void testRequiredIndexesAreCreated() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            migrator.migrateToLatest(conn);
            
            // Check for some key indexes
            List<String> requiredIndexes = List.of(
                "idx_vectors_project_id",
                "idx_graph_entities_project_id",
                "idx_graph_relations_project_id",
                "idx_documents_project_id"
            );
            
            for (String index : requiredIndexes) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='index' AND name='" + index + "'")) {
                    assertTrue(rs.next(), "Index " + index + " should exist");
                }
            }
        }
    }

    /**
     * Test that foreign key constraints are created correctly.
     */
    @Test
    void testForeignKeyConstraintsExist() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            migrator.migrateToLatest(conn);
            
            // Verify foreign key info for documents table
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_list(documents)")) {
                assertTrue(rs.next(), "documents table should have foreign key to projects");
            }
            
            // Verify foreign key info for vectors table
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_list(vectors)")) {
                assertTrue(rs.next(), "vectors table should have foreign keys");
            }
        }
    }

    /**
     * Test that schema can be verified after restart.
     */
    @Test
    void testSchemaPersistedAfterRestart() throws Exception {
        // Apply migrations
        try (Connection conn = connectionManager.createConnection()) {
            migrator.migrateToLatest(conn);
        }
        
        // Close and reopen
        connectionManager.close();
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Verify schema still exists
        try (Connection conn = connectionManager.createConnection()) {
            int version = migrator.getCurrentVersion(conn);
            assertTrue(version > 0, "Version should persist after restart");
            
            // Verify tables still exist
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Tables should persist after restart");
            }
        }
    }
}
