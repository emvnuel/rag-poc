package br.edu.ifba.lightrag.storage.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.storage.DocStatusStorage;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer that creates storage implementations for SQLite backend.
 * 
 * <p>This provider is activated when {@code lightrag.storage.backend=sqlite}
 * is set in the application configuration.</p>
 * 
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Creates and manages SQLiteConnectionManager</li>
 *   <li>Runs schema migrations on startup</li>
 *   <li>Produces storage interface implementations for CDI injection</li>
 * </ul>
 * 
 * <p>Example configuration:</p>
 * <pre>
 * lightrag.storage.backend=sqlite
 * lightrag.storage.sqlite.path=data/rag.db
 * </pre>
 */
@ApplicationScoped
@IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
public class SQLiteStorageProvider {

    private static final Logger LOG = Logger.getLogger(SQLiteStorageProvider.class);

    @ConfigProperty(name = "lightrag.storage.sqlite.path", defaultValue = "data/rag.db")
    String databasePath;

    @ConfigProperty(name = "lightrag.storage.sqlite.extensions.path")
    java.util.Optional<String> extensionsPath;

    @ConfigProperty(name = "lightrag.storage.sqlite.read-pool-size", defaultValue = "4")
    int readPoolSize;

    @ConfigProperty(name = "lightrag.storage.sqlite.busy-timeout", defaultValue = "30000")
    long busyTimeoutMs;

    @ConfigProperty(name = "lightrag.storage.sqlite.wal-mode", defaultValue = "true")
    boolean walMode;

    // Vector dimension - uses same config property as PostgreSQL for consistency
    // This ensures LIGHTRAG_VECTOR_DIMENSION env var works for both backends
    @ConfigProperty(name = "lightrag.vector.dimension", defaultValue = "768")
    int vectorDimension;

    // Vector table name - uses same config property as PostgreSQL for consistency
    // This ensures LIGHTRAG_VECTOR_TABLE_NAME env var works for both backends
    @ConfigProperty(name = "lightrag.vector.table.name", defaultValue = "vectors")
    String vectorTableName;

    @ConfigProperty(name = "lightrag.storage.sqlite.vector.distance", defaultValue = "COSINE")
    String vectorDistance;

    private SQLiteConnectionManager connectionManager;
    private SQLiteExtensionLoader extensionLoader;
    private boolean initialized = false;
    
    // Cached storage instances (created lazily on first access)
    private SQLiteGraphStorage graphStorage;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteExtractionCacheStorage extractionCacheStorage;
    private SQLiteKVStorage kvStorage;
    private SQLiteDocStatusStorage docStatusStorage;

    /**
     * Initializes the SQLite storage on application startup.
     */
    @PostConstruct
    void initialize() {
        LOG.infof("Initializing SQLite storage with database: %s", databasePath);

        // Create extension loader
        if (extensionsPath.isPresent() && !extensionsPath.get().isBlank()) {
            extensionLoader = new SQLiteExtensionLoader(extensionsPath.get());
        } else {
            extensionLoader = new SQLiteExtensionLoader();
        }

        // Create connection manager
        connectionManager = new SQLiteConnectionManager(
            databasePath,
            Duration.ofMillis(busyTimeoutMs),
            walMode,
            readPoolSize
        );

        // Run schema migrations
        try {
            runMigrations();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run SQLite schema migrations", e);
        }

        initialized = true;
        LOG.info("SQLite storage initialized successfully");
    }

    /**
     * Cleans up resources on application shutdown.
     */
    @PreDestroy
    void shutdown() {
        LOG.info("Shutting down SQLite storage");
        
        // Close storage instances
        closeQuietly(graphStorage);
        closeQuietly(vectorStorage);
        closeQuietly(extractionCacheStorage);
        closeQuietly(kvStorage);
        closeQuietly(docStatusStorage);
        
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Produces the GraphStorage implementation for SQLite.
     * 
     * @return SQLiteGraphStorage instance
     */
    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    public GraphStorage produceGraphStorage() {
        ensureInitialized();
        if (graphStorage == null) {
            graphStorage = new SQLiteGraphStorage(connectionManager);
            graphStorage.initialize().join();
            LOG.info("Created SQLiteGraphStorage instance");
        }
        return graphStorage;
    }

    /**
     * Produces the VectorStorage implementation for SQLite.
     * 
     * @return SQLiteVectorStorage instance
     */
    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    public VectorStorage produceVectorStorage() {
        ensureInitialized();
        if (vectorStorage == null) {
            vectorStorage = new SQLiteVectorStorage(connectionManager, vectorDimension, vectorTableName);
            vectorStorage.initialize().join();
            LOG.infof("Created SQLiteVectorStorage instance with dimension %d, table '%s'", vectorDimension, vectorTableName);
        }
        return vectorStorage;
    }

    /**
     * Produces the ExtractionCacheStorage implementation for SQLite.
     * 
     * @return SQLiteExtractionCacheStorage instance
     */
    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    public ExtractionCacheStorage produceExtractionCacheStorage() {
        ensureInitialized();
        if (extractionCacheStorage == null) {
            extractionCacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
            extractionCacheStorage.initialize().join();
            LOG.info("Created SQLiteExtractionCacheStorage instance");
        }
        return extractionCacheStorage;
    }

    /**
     * Produces the KVStorage implementation for SQLite.
     * 
     * @return SQLiteKVStorage instance
     */
    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    public KVStorage produceKVStorage() {
        ensureInitialized();
        if (kvStorage == null) {
            kvStorage = new SQLiteKVStorage(connectionManager);
            kvStorage.initialize().join();
            LOG.info("Created SQLiteKVStorage instance");
        }
        return kvStorage;
    }

    /**
     * Produces the DocStatusStorage implementation for SQLite.
     * 
     * @return SQLiteDocStatusStorage instance
     */
    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    public DocStatusStorage produceDocStatusStorage() {
        ensureInitialized();
        if (docStatusStorage == null) {
            docStatusStorage = new SQLiteDocStatusStorage(connectionManager);
            docStatusStorage.initialize().join();
            LOG.info("Created SQLiteDocStatusStorage instance");
        }
        return docStatusStorage;
    }

    /**
     * Produces the SQLiteConnectionManager for injection into repositories.
     * 
     * @return the connection manager
     */
    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    public SQLiteConnectionManager produceConnectionManager() {
        ensureInitialized();
        return connectionManager;
    }

    /**
     * Gets the connection manager for storage implementations.
     * 
     * @return the connection manager
     */
    public SQLiteConnectionManager getConnectionManager() {
        ensureInitialized();
        return connectionManager;
    }

    /**
     * Gets the extension loader for storage implementations.
     * 
     * @return the extension loader
     */
    public SQLiteExtensionLoader getExtensionLoader() {
        return extensionLoader;
    }

    /**
     * Checks if extensions are available for the current platform.
     * 
     * @return true if extensions can be loaded
     */
    public boolean areExtensionsAvailable() {
        return extensionLoader.areExtensionsAvailable();
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SQLite storage not initialized");
        }
    }

    private void runMigrations() throws SQLException {
        LOG.info("Running SQLite schema migrations");
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        
        Connection conn = null;
        try {
            conn = connectionManager.getWriteConnection();
            int currentVersion = migrator.getCurrentVersion(conn);
            LOG.infof("Current schema version: %d", currentVersion);
            
            migrator.migrateToLatest(conn);
            
            int newVersion = migrator.getCurrentVersion(conn);
            if (newVersion > currentVersion) {
                LOG.infof("Migrated schema from version %d to %d", currentVersion, newVersion);
            } else {
                LOG.info("Schema is already up to date");
            }
        } finally {
            // IMPORTANT: Must call releaseWriteConnection to release the lock!
            // The lock is NOT released when the connection is closed.
            if (conn != null) {
                connectionManager.releaseWriteConnection(conn);
            }
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warnf("Failed to close storage: %s", e.getMessage());
            }
        }
    }
}
