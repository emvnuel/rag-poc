package br.edu.ifba.lightrag.storage.impl;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.storage.DocStatusStorage;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;

/**
 * Contract test implementation for SQLite storage backend.
 * 
 * <p>This class runs the full storage contract test suite against
 * the SQLite storage implementations, verifying they behave correctly
 * and identically to other backends.</p>
 * 
 * <p>Uses a temporary database file for isolation between test runs.</p>
 */
class SQLiteStorageContractTest extends StorageContractTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;
    private SQLiteKVStorage kvStorage;
    private SQLiteDocStatusStorage docStatusStorage;
    private SQLiteExtractionCacheStorage extractionCacheStorage;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("contract-test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize all storage implementations
        vectorStorage = new SQLiteVectorStorage(connectionManager, getVectorDimension());
        vectorStorage.initialize().join();
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        kvStorage = new SQLiteKVStorage(connectionManager);
        kvStorage.initialize().join();
        
        docStatusStorage = new SQLiteDocStatusStorage(connectionManager);
        docStatusStorage.initialize().join();
        
        extractionCacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
        extractionCacheStorage.initialize().join();
        
        // Call parent setup to set projectId and documentId
        super.setUpContract();
        
        // Create project and document records to satisfy foreign key constraints
        createProjectInternal(projectId, "Contract Test Project");
        createDocument(documentId, projectId);
        
        // Create project graph for graph tests
        graphStorage.createProjectGraph(projectId).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vectorStorage != null) vectorStorage.close();
        if (graphStorage != null) graphStorage.close();
        if (kvStorage != null) kvStorage.close();
        if (docStatusStorage != null) docStatusStorage.close();
        if (extractionCacheStorage != null) extractionCacheStorage.close();
        if (connectionManager != null) connectionManager.close();
    }

    /**
     * Creates a project record to satisfy foreign key constraints.
     * Overrides the parent method for use by contract tests.
     */
    @Override
    protected void createProject(String projId) throws Exception {
        createProjectInternal(projId, "Test Project " + projId.substring(0, 8));
    }

    /**
     * Creates a project record with a name.
     */
    private void createProjectInternal(String projId, String name) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT OR IGNORE INTO projects (id, name, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))")) {
            stmt.setString(1, projId);
            stmt.setString(2, name);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    /**
     * Creates a document record to satisfy foreign key constraints.
     */
    private void createDocument(String docId, String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO documents (id, project_id, type, status, created_at, updated_at) " +
                "VALUES (?, ?, 'TXT', 'NOT_PROCESSED', datetime('now'), datetime('now'))")) {
            stmt.setString(1, docId);
            stmt.setString(2, projId);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    @Override
    protected VectorStorage getVectorStorage() {
        return vectorStorage;
    }

    @Override
    protected GraphStorage getGraphStorage() {
        return graphStorage;
    }

    @Override
    protected KVStorage getKVStorage() {
        return kvStorage;
    }

    @Override
    protected DocStatusStorage getDocStatusStorage() {
        return docStatusStorage;
    }

    @Override
    protected ExtractionCacheStorage getExtractionCacheStorage() {
        return extractionCacheStorage;
    }

    @Override
    protected int getVectorDimension() {
        return 384;
    }
}
