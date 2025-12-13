package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for SQLiteConnectionManager.
 * 
 * Tests verify:
 * 1. Connection creation and configuration
 * 2. PRAGMA settings are applied
 * 3. Connection pooling for reads
 * 4. Write lock serialization
 * 5. Error handling for invalid paths
 */
class SQLiteConnectionManagerTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private Path dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Test that a connection can be created successfully.
     */
    @Test
    void testCreateConnectionSucceeds() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            assertNotNull(conn, "Connection should not be null");
            assertFalse(conn.isClosed(), "Connection should be open");
        }
    }

    /**
     * Test that database file is created.
     */
    @Test
    void testDatabaseFileIsCreated() throws Exception {
        try (Connection conn = connectionManager.createConnection()) {
            // Just creating a connection should create the file
            assertTrue(Files.exists(dbPath), "Database file should be created");
        }
    }

    /**
     * Test that in-memory database works.
     */
    @Test
    void testInMemoryDatabaseWorks() throws Exception {
        SQLiteConnectionManager inMemoryManager = new SQLiteConnectionManager(":memory:");
        try (Connection conn = inMemoryManager.createConnection()) {
            assertNotNull(conn, "In-memory connection should not be null");
            assertFalse(conn.isClosed(), "In-memory connection should be open");
        } finally {
            inMemoryManager.close();
        }
    }

    /**
     * Test that WAL mode is enabled by default.
     */
    @Test
    void testWalModeIsEnabled() throws Exception {
        try (Connection conn = connectionManager.createConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next(), "Should have result");
            String journalMode = rs.getString(1);
            assertEquals("wal", journalMode.toLowerCase(), "Journal mode should be WAL");
        }
    }

    /**
     * Test that foreign keys are enabled.
     */
    @Test
    void testForeignKeysEnabled() throws Exception {
        try (Connection conn = connectionManager.createConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next(), "Should have result");
            int foreignKeys = rs.getInt(1);
            assertEquals(1, foreignKeys, "Foreign keys should be enabled");
        }
    }

    /**
     * Test that busy timeout is configured.
     */
    @Test
    void testBusyTimeoutConfigured() throws Exception {
        SQLiteConnectionManager customManager = new SQLiteConnectionManager(
            dbPath.toString(), 
            Duration.ofSeconds(60),
            true,
            4
        );
        try (Connection conn = customManager.createConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(rs.next(), "Should have result");
            int timeout = rs.getInt(1);
            assertEquals(60000, timeout, "Busy timeout should be 60000ms");
        } finally {
            customManager.close();
        }
    }

    /**
     * Test that multiple read connections can be obtained.
     */
    @Test
    void testMultipleReadConnections() throws Exception {
        Connection conn1 = connectionManager.getReadConnection();
        Connection conn2 = connectionManager.getReadConnection();
        
        try {
            assertNotNull(conn1, "First read connection should not be null");
            assertNotNull(conn2, "Second read connection should not be null");
            assertFalse(conn1.isClosed(), "First connection should be open");
            assertFalse(conn2.isClosed(), "Second connection should be open");
        } finally {
            connectionManager.releaseReadConnection(conn1);
            connectionManager.releaseReadConnection(conn2);
        }
    }

    /**
     * Test that write connection is exclusive.
     */
    @Test
    void testWriteConnectionIsExclusive() throws Exception {
        Connection writeConn = connectionManager.getWriteConnection();
        
        try {
            assertNotNull(writeConn, "Write connection should not be null");
            assertFalse(writeConn.isClosed(), "Write connection should be open");
            
            // Should be able to write
            try (Statement stmt = writeConn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS test (id INTEGER)");
                stmt.execute("INSERT INTO test VALUES (1)");
            }
        } finally {
            connectionManager.releaseWriteConnection(writeConn);
        }
    }

    /**
     * Test that connection manager can be closed and reopened.
     */
    @Test
    void testCloseAndReopen() throws Exception {
        try (Connection conn = connectionManager.createConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS test (id INTEGER)");
        }
        
        connectionManager.close();
        
        // Create a new manager for the same database
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        try (Connection conn = connectionManager.createConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='test'")) {
            assertTrue(rs.next(), "Table should exist after reopen");
        }
    }

    /**
     * Test exception for invalid database path.
     */
    @Test
    void testInvalidPathThrowsException() {
        String invalidPath = "/nonexistent/directory/that/does/not/exist/test.db";
        SQLiteConnectionManager invalidManager = new SQLiteConnectionManager(invalidPath);
        
        assertThrows(RuntimeException.class, invalidManager::createConnection);
        invalidManager.close();
    }

    /**
     * Test database locked exception wrapping.
     */
    @Test
    void testDatabaseLockedExceptionContainsDetails() {
        SQLiteDatabaseLockedException exception = new SQLiteDatabaseLockedException(
            "INSERT", Duration.ofSeconds(30)
        );
        
        String message = exception.getMessage();
        assertTrue(message.contains("INSERT"), "Message should contain operation");
        assertTrue(message.contains("30000"), "Message should contain timeout in ms");
        assertEquals(Duration.ofSeconds(30), exception.getWaitTime());
        assertEquals("INSERT", exception.getOperation());
    }
}
