package br.edu.ifba.lightrag.storage.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;
import org.sqlite.SQLiteConfig;

/**
 * Manages SQLite connections with extension loading and pragma configuration.
 * Thread-safe with connection pooling for read operations.
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>WAL mode enabled by default for better concurrency</li>
 *   <li>Connection pool for read operations</li>
 *   <li>Exclusive write connection with ReentrantLock</li>
 *   <li>Configurable busy timeout for lock waiting</li>
 *   <li>Foreign key enforcement enabled</li>
 *   <li>Edge deployment mode for resource-constrained devices (256MB memory)</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * SQLiteConnectionManager manager = new SQLiteConnectionManager("data/rag.db");
 * try (Connection conn = manager.createConnection()) {
 *     // Use connection
 * }
 * </pre>
 * 
 * <p>Edge deployment usage:</p>
 * <pre>
 * SQLiteConnectionManager manager = SQLiteConnectionManager.forEdgeDeployment("data/rag.db");
 * </pre>
 */
public final class SQLiteConnectionManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SQLiteConnectionManager.class);

    private static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(30);
    private static final boolean DEFAULT_WAL_MODE = true;
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final int DEFAULT_CACHE_SIZE = -2000; // 2MB
    private static final long DEFAULT_MMAP_SIZE = 268435456L; // 256MB

    // Edge deployment settings (optimized for 256MB total memory)
    private static final int EDGE_POOL_SIZE = 2;
    private static final int EDGE_CACHE_SIZE = -500; // 500KB
    private static final long EDGE_MMAP_SIZE = 0L; // Disable mmap to save memory
    private static final boolean EDGE_TEMP_STORE_FILE = true;

    private final String databasePath;
    private final Duration busyTimeout;
    private final boolean walMode;
    private final int cacheSize;
    private final long mmapSize;
    private final boolean tempStoreFile;
    private final BlockingQueue<Connection> readPool;
    private final ReentrantLock writeLock;
    
    private Connection writeConnection;
    private volatile boolean closed = false;

    /**
     * Creates a connection manager with default settings.
     * 
     * @param databasePath path to SQLite database file, or ":memory:" for in-memory
     */
    public SQLiteConnectionManager(String databasePath) {
        this(databasePath, DEFAULT_BUSY_TIMEOUT, DEFAULT_WAL_MODE, DEFAULT_POOL_SIZE);
    }

    /**
     * Creates a connection manager with custom settings.
     * 
     * @param databasePath path to SQLite database file
     * @param busyTimeout how long to wait for locks
     * @param walMode whether to enable WAL mode
     * @param readPoolSize number of connections in read pool
     */
    public SQLiteConnectionManager(String databasePath, Duration busyTimeout, 
            boolean walMode, int readPoolSize) {
        this(databasePath, busyTimeout, walMode, readPoolSize, 
             DEFAULT_CACHE_SIZE, DEFAULT_MMAP_SIZE, false);
    }

    /**
     * Creates a connection manager with full custom settings including memory options.
     * 
     * @param databasePath path to SQLite database file
     * @param busyTimeout how long to wait for locks
     * @param walMode whether to enable WAL mode
     * @param readPoolSize number of connections in read pool
     * @param cacheSize SQLite cache size (negative = KB, positive = pages)
     * @param mmapSize memory-mapped I/O size in bytes (0 to disable)
     * @param tempStoreFile true to store temp tables in file (saves memory)
     */
    public SQLiteConnectionManager(String databasePath, Duration busyTimeout, 
            boolean walMode, int readPoolSize, int cacheSize, long mmapSize,
            boolean tempStoreFile) {
        this.databasePath = databasePath;
        this.busyTimeout = busyTimeout;
        this.walMode = walMode;
        this.cacheSize = cacheSize;
        this.mmapSize = mmapSize;
        this.tempStoreFile = tempStoreFile;
        this.readPool = new ArrayBlockingQueue<>(readPoolSize);
        this.writeLock = new ReentrantLock();
    }

    /**
     * Creates a connection manager optimized for edge deployment.
     * 
     * <p>Edge settings:</p>
     * <ul>
     *   <li>Pool size: 2 connections (reduced from 4)</li>
     *   <li>Cache size: 500KB (reduced from 2MB)</li>
     *   <li>MMAP: disabled (saves memory)</li>
     *   <li>Temp storage: file-based (saves memory)</li>
     * </ul>
     * 
     * @param databasePath path to SQLite database file
     * @return connection manager configured for edge deployment
     */
    public static SQLiteConnectionManager forEdgeDeployment(String databasePath) {
        LOG.info("Creating SQLite connection manager for edge deployment (low-memory mode)");
        return new SQLiteConnectionManager(
            databasePath,
            DEFAULT_BUSY_TIMEOUT,
            DEFAULT_WAL_MODE,
            EDGE_POOL_SIZE,
            EDGE_CACHE_SIZE,
            EDGE_MMAP_SIZE,
            EDGE_TEMP_STORE_FILE
        );
    }

    /**
     * Creates a new connection with pragmas configured.
     * 
     * @return configured Connection
     * @throws RuntimeException if connection creation fails
     */
    public Connection createConnection() {
        if (closed) {
            throw new IllegalStateException("Connection manager is closed");
        }

        // Create parent directory if it doesn't exist (skip for in-memory databases)
        if (!databasePath.equals(":memory:") && !databasePath.startsWith(":memory:")) {
            try {
                Path dbPath = Paths.get(databasePath);
                Path parentDir = dbPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                    LOG.infof("Created database directory: %s", parentDir);
                }
            } catch (Exception e) {
                LOG.warnf("Could not create parent directory for %s: %s", databasePath, e.getMessage());
            }
        }

        try {
            SQLiteConfig config = new SQLiteConfig();
            config.enforceForeignKeys(true);
            config.setBusyTimeout((int) busyTimeout.toMillis());
            config.setCacheSize(cacheSize);
            config.enableLoadExtension(true);

            String url = "jdbc:sqlite:" + databasePath;
            Connection conn = DriverManager.getConnection(url, config.toProperties());
            
            applyPragmas(conn);
            
            LOG.debugf("Created SQLite connection to %s", databasePath);
            return conn;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create SQLite connection to " + databasePath, e);
        }
    }

    /**
     * Gets a connection for read operations from the pool.
     * Creates a new connection if pool is empty.
     * 
     * @return pooled read Connection
     */
    public Connection getReadConnection() {
        if (closed) {
            throw new IllegalStateException("Connection manager is closed");
        }

        Connection conn = readPool.poll();
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    return conn;
                }
            } catch (SQLException e) {
                LOG.debug("Read connection was closed, creating new one", e);
            }
        }
        
        return createConnection();
    }

    /**
     * Returns a read connection to the pool.
     * 
     * @param conn the connection to release
     */
    public void releaseReadConnection(Connection conn) {
        if (conn == null) {
            return;
        }
        
        try {
            if (!conn.isClosed() && !closed) {
                if (!readPool.offer(conn)) {
                    // Pool is full, close the connection
                    conn.close();
                }
            } else {
                conn.close();
            }
        } catch (SQLException e) {
            LOG.debug("Error releasing read connection", e);
        }
    }

    /**
     * Gets exclusive connection for write operations.
     * Only one write connection can be active at a time.
     * 
     * @return write Connection with lock held
     */
    public Connection getWriteConnection() {
        if (closed) {
            throw new IllegalStateException("Connection manager is closed");
        }

        writeLock.lock();
        try {
            if (writeConnection == null || writeConnection.isClosed()) {
                writeConnection = createConnection();
            }
            return writeConnection;
        } catch (SQLException e) {
            writeLock.unlock();
            throw new RuntimeException("Failed to get write connection", e);
        }
    }

    /**
     * Releases the write connection lock.
     * 
     * @param conn the write connection (must match current write connection)
     */
    public void releaseWriteConnection(Connection conn) {
        if (conn == writeConnection) {
            writeLock.unlock();
        }
    }

    /**
     * Applies SQLite pragmas for performance.
     * Uses instance configuration for memory settings.
     * 
     * @param conn the connection to configure
     * @throws SQLException if pragma execution fails
     */
    public void applyPragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // WAL mode for better concurrency
            if (walMode) {
                stmt.execute("PRAGMA journal_mode = WAL");
            }
            
            // Synchronous mode for WAL
            stmt.execute("PRAGMA synchronous = NORMAL");
            
            // Memory-mapped I/O (configurable, 0 to disable for edge deployment)
            stmt.execute("PRAGMA mmap_size = " + mmapSize);
            
            // Temp storage (MEMORY for performance, FILE for low memory)
            if (tempStoreFile) {
                stmt.execute("PRAGMA temp_store = FILE");
            } else {
                stmt.execute("PRAGMA temp_store = MEMORY");
            }
            
            LOG.debugf("Applied SQLite pragmas (mmap=%d, tempStoreFile=%s)", Long.valueOf(mmapSize), Boolean.valueOf(tempStoreFile));
        }
    }

    /**
     * Gets the configured cache size.
     * 
     * @return cache size (negative = KB, positive = pages)
     */
    public int getCacheSize() {
        return cacheSize;
    }

    /**
     * Gets the configured mmap size.
     * 
     * @return mmap size in bytes (0 = disabled)
     */
    public long getMmapSize() {
        return mmapSize;
    }

    /**
     * Checks if temp storage is file-based.
     * 
     * @return true if temp tables are stored in files
     */
    public boolean isTempStoreFile() {
        return tempStoreFile;
    }

    /**
     * Gets the database path.
     * 
     * @return path to database file
     */
    public String getDatabasePath() {
        return databasePath;
    }

    /**
     * Gets the configured busy timeout.
     * 
     * @return busy timeout duration
     */
    public Duration getBusyTimeout() {
        return busyTimeout;
    }

    /**
     * Checks if WAL mode is enabled.
     * 
     * @return true if WAL mode is enabled
     */
    public boolean isWalModeEnabled() {
        return walMode;
    }

    /**
     * Closes the connection manager and all connections.
     */
    public void close() {
        closed = true;
        
        // Close write connection
        if (writeConnection != null) {
            try {
                writeConnection.close();
            } catch (SQLException e) {
                LOG.debug("Error closing write connection", e);
            }
            writeConnection = null;
        }
        
        // Close all pooled connections
        Connection conn;
        while ((conn = readPool.poll()) != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                LOG.debug("Error closing pooled connection", e);
            }
        }
        
        LOG.infof("Closed SQLite connection manager for %s", databasePath);
    }
}
