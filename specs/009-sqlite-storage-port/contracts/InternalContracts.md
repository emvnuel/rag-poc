# Internal Contracts: SQLite Storage Port

**Feature Branch**: `009-sqlite-storage-port`  
**Created**: 2024-12-13

## Overview

This document defines the internal Java interfaces and contracts for SQLite storage implementations. These contracts ensure feature parity with PostgreSQL implementations while leveraging sqlite-graph and sqlite-vector extensions.

---

## 1. Storage Provider Configuration

### Configuration Properties

```properties
# Storage backend selection
lightrag.storage.backend=sqlite              # Options: postgresql, sqlite

# SQLite-specific configuration
lightrag.storage.sqlite.path=data/rag.db     # Database file path
lightrag.storage.sqlite.extensions.path=/opt/sqlite/extensions  # Native library path

# Vector configuration
lightrag.vector.dimension=384                # Embedding dimension
lightrag.vector.distance=COSINE              # L2, COSINE, DOT, L1

# Graph configuration  
lightrag.graph.enabled=true
```

### CDI Producer Contract

```java
/**
 * CDI producer that creates storage implementations based on configuration.
 * Uses @IfBuildProperty to select between PostgreSQL and SQLite at build time.
 */
@ApplicationScoped
public class SQLiteStorageProvider {

    /**
     * Produces the GraphStorage implementation for SQLite.
     * @return SQLiteGraphStorage when sqlite backend is configured
     */
    @Produces
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    GraphStorage produceGraphStorage();

    /**
     * Produces the VectorStorage implementation for SQLite.
     * @return SQLiteVectorStorage when sqlite backend is configured
     */
    @Produces
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    VectorStorage produceVectorStorage();

    /**
     * Produces the ExtractionCacheStorage implementation for SQLite.
     * @return SQLiteExtractionCacheStorage when sqlite backend is configured
     */
    @Produces
    @IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
    ExtractionCacheStorage produceExtractionCacheStorage();
}
```

---

## 2. SQLiteGraphStorage Contract

Implements `GraphStorage` interface using sqlite-graph extension.

### Interface: GraphStorage (existing)

```java
public interface GraphStorage {
    
    // Lifecycle
    CompletableFuture<Void> initialize();
    CompletableFuture<Void> createProjectGraph(UUID projectId);
    CompletableFuture<Void> deleteProjectGraph(UUID projectId);
    CompletableFuture<Boolean> graphExists(UUID projectId);
    
    // Entity Operations
    CompletableFuture<Void> upsertEntity(UUID projectId, Entity entity);
    CompletableFuture<Void> upsertEntities(UUID projectId, List<Entity> entities);
    CompletableFuture<Optional<Entity>> getEntity(UUID projectId, String name);
    CompletableFuture<List<Entity>> getEntities(UUID projectId, Collection<String> names);
    CompletableFuture<List<Entity>> getAllEntities(UUID projectId);
    CompletableFuture<Void> deleteEntity(UUID projectId, String name);
    CompletableFuture<Void> deleteEntities(UUID projectId, Collection<String> names);
    CompletableFuture<Void> updateEntityDescription(UUID projectId, String name, 
            String description, List<String> sourceIds);
    
    // Relation Operations
    CompletableFuture<Void> upsertRelation(UUID projectId, Relation relation);
    CompletableFuture<Void> upsertRelations(UUID projectId, List<Relation> relations);
    CompletableFuture<Optional<Relation>> getRelation(UUID projectId, String srcId, String tgtId);
    CompletableFuture<List<Relation>> getRelationsForEntity(UUID projectId, String entityName);
    CompletableFuture<List<Relation>> getAllRelations(UUID projectId);
    CompletableFuture<Void> deleteRelation(UUID projectId, String srcId, String tgtId);
    CompletableFuture<Void> deleteRelations(UUID projectId, Collection<String> relationKeys);
    
    // Query Operations
    CompletableFuture<List<Entity>> getEntitiesBySourceChunks(UUID projectId, 
            Collection<String> chunkIds);
    CompletableFuture<List<Relation>> getRelationsBySourceChunks(UUID projectId, 
            Collection<String> chunkIds);
    CompletableFuture<List<Entity>> getEntitiesBatch(UUID projectId, int offset, int limit);
    CompletableFuture<List<Relation>> getRelationsBatch(UUID projectId, int offset, int limit);
    
    // Traversal
    CompletableFuture<GraphSubgraph> traverse(UUID projectId, String startEntity, int maxDepth);
    CompletableFuture<GraphSubgraph> traverseBFS(UUID projectId, String startEntity, 
            int maxDepth, int maxNodes);
    CompletableFuture<List<String>> findShortestPath(UUID projectId, String source, String target);
    
    // Stats
    CompletableFuture<GraphStats> getStats(UUID projectId);
}
```

### SQLiteGraphStorage Implementation Notes

```java
/**
 * SQLite implementation of GraphStorage using sqlite-graph extension.
 * 
 * Key implementation details:
 * - Uses Cypher queries via cypher_execute() function
 * - Project isolation via project_id property in nodes/edges
 * - Entity names normalized to lowercase for case-insensitive matching
 * - Source chunk IDs stored as JSON array in properties
 * - Uses ReentrantLock for write serialization
 */
@ApplicationScoped
public class SQLiteGraphStorage implements GraphStorage {
    
    // Cypher query templates
    static final String CREATE_ENTITY = """
        MERGE (e:Entity {name: $name, project_id: $projectId})
        SET e.entity_type = $entityType,
            e.description = $description,
            e.document_id = $documentId,
            e.source_chunk_ids = $sourceChunkIds
        """;
    
    static final String GET_ENTITY = """
        MATCH (e:Entity {name: $name, project_id: $projectId})
        RETURN e
        """;
    
    static final String CREATE_RELATION = """
        MATCH (src:Entity {name: $srcId, project_id: $projectId}),
              (tgt:Entity {name: $tgtId, project_id: $projectId})
        MERGE (src)-[r:RELATED_TO]->(tgt)
        SET r.description = $description,
            r.keywords = $keywords,
            r.weight = $weight,
            r.document_id = $documentId,
            r.source_chunk_ids = $sourceChunkIds,
            r.project_id = $projectId
        """;
    
    // BFS traversal using recursive CTE (fallback for variable-length paths)
    static final String TRAVERSE_BFS = """
        WITH RECURSIVE traverse(name, depth, path) AS (
            SELECT name, 0, name 
            FROM knowledge_graph_nodes 
            WHERE json_extract(properties, '$.name') = ? 
              AND json_extract(properties, '$.project_id') = ?
            UNION ALL
            SELECT n.name, t.depth + 1, t.path || ',' || n.name
            FROM traverse t
            JOIN knowledge_graph_edges e ON ...
            WHERE t.depth < ? AND ...
        )
        SELECT DISTINCT name FROM traverse
        """;
}
```

---

## 3. SQLiteVectorStorage Contract

Implements `VectorStorage` interface using sqlite-vector extension.

### Interface: VectorStorage (existing)

```java
public interface VectorStorage {
    
    // Lifecycle
    CompletableFuture<Void> initialize();
    
    // Upsert Operations
    CompletableFuture<Void> upsert(String id, float[] vector, VectorMetadata metadata);
    CompletableFuture<Void> upsertBatch(List<VectorEntry> entries);
    
    // Query Operations
    CompletableFuture<List<VectorSearchResult>> query(float[] queryVector, int topK, 
            VectorFilter filter);
    CompletableFuture<Optional<VectorEntry>> get(String id);
    
    // Delete Operations
    CompletableFuture<Void> delete(String id);
    CompletableFuture<Void> deleteBatch(Collection<String> ids);
    CompletableFuture<Void> deleteEntityEmbeddings(UUID projectId, Collection<String> entityNames);
    CompletableFuture<Void> deleteChunkEmbeddings(UUID projectId, Collection<String> chunkIds);
    CompletableFuture<List<String>> getChunkIdsByDocumentId(UUID projectId, UUID documentId);
    
    // Stats
    CompletableFuture<Long> size();
    CompletableFuture<Void> clear();
}
```

### SQLiteVectorStorage Implementation Notes

```java
/**
 * SQLite implementation of VectorStorage using sqlite-vector extension.
 * 
 * Key implementation details:
 * - Vectors stored as BLOB in Float32 format
 * - Uses vector_init() on connection startup
 * - Uses vector_quantize() after bulk inserts for fast search
 * - Uses vector_quantize_scan() for similarity queries
 * - Joins with main table to get full metadata
 */
@ApplicationScoped
public class SQLiteVectorStorage implements VectorStorage {
    
    // Initialize vector column (per connection)
    void initializeVectorColumn(Connection conn) {
        String sql = "SELECT vector_init('vectors', 'vector', " +
            "'type=FLOAT32,dimension=" + dimension + ",distance=" + distance + "')";
        conn.createStatement().execute(sql);
    }
    
    // Insert with vector encoding
    static final String UPSERT = """
        INSERT OR REPLACE INTO vectors (id, project_id, type, content, vector, document_id, chunk_index)
        VALUES (?, ?, ?, ?, vector_as_f32(?), ?, ?)
        """;
    
    // Similarity search with metadata join
    static final String QUERY = """
        SELECT v.id, v.type, v.content, v.document_id, v.chunk_index, v.project_id, s.distance
        FROM vector_quantize_scan('vectors', 'vector', ?, ?) AS s
        JOIN vectors v ON v.rowid = s.rowid
        WHERE v.project_id = ?
          AND v.type = ?
        """;
    
    // Quantize after bulk insert
    void quantize() {
        conn.createStatement().execute("SELECT vector_quantize('vectors', 'vector')");
        conn.createStatement().execute("SELECT vector_quantize_preload('vectors', 'vector')");
    }
}
```

---

## 4. SQLiteExtractionCacheStorage Contract

Implements `ExtractionCacheStorage` interface.

### Interface: ExtractionCacheStorage (existing)

```java
public interface ExtractionCacheStorage {
    
    CompletableFuture<Void> initialize();
    
    CompletableFuture<Void> store(UUID projectId, CacheType cacheType, UUID chunkId,
            String contentHash, String result, Integer tokensUsed);
    
    CompletableFuture<Optional<ExtractionCache>> get(UUID projectId, CacheType cacheType, 
            String contentHash);
    
    CompletableFuture<List<ExtractionCache>> getByChunkId(UUID projectId, UUID chunkId);
    
    CompletableFuture<Void> deleteByProject(UUID projectId);
}
```

### SQLiteExtractionCacheStorage Implementation Notes

```java
/**
 * SQLite implementation of ExtractionCacheStorage.
 * Uses standard SQL - no special extensions required.
 */
@ApplicationScoped
public class SQLiteExtractionCacheStorage implements ExtractionCacheStorage {
    
    static final String STORE = """
        INSERT OR REPLACE INTO extraction_cache 
        (id, project_id, cache_type, chunk_id, content_hash, result, tokens_used)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    
    static final String GET_BY_HASH = """
        SELECT * FROM extraction_cache 
        WHERE project_id = ? AND cache_type = ? AND content_hash = ?
        """;
}
```

---

## 5. Connection Management

### SQLiteConnectionManager

```java
/**
 * Manages SQLite connections with extension loading and pragma configuration.
 * Thread-safe with connection pooling for read operations.
 */
@ApplicationScoped
public class SQLiteConnectionManager {
    
    /**
     * Creates a new connection with extensions loaded and pragmas configured.
     * @return Configured Connection
     */
    Connection createConnection();
    
    /**
     * Gets a connection for read operations (from pool).
     * @return Pooled Connection
     */
    Connection getReadConnection();
    
    /**
     * Gets exclusive connection for write operations.
     * @return Write Connection with lock held
     */
    Connection getWriteConnection();
    
    /**
     * Releases a write connection.
     */
    void releaseWriteConnection(Connection conn);
    
    /**
     * Loads native extensions (sqlite-graph, sqlite-vector).
     * Extracts from JAR to temp directory if needed.
     */
    void loadExtensions(Connection conn);
    
    /**
     * Applies SQLite pragmas for performance.
     */
    void applyPragmas(Connection conn);
    
    /**
     * Initializes schema if needed.
     */
    void initializeSchema(Connection conn);
}
```

---

## 6. Extension Loader Contract

### SQLiteExtensionLoader

```java
/**
 * Handles loading of native SQLite extensions.
 * Supports bundled extensions (in JAR) and external paths.
 */
@ApplicationScoped
public class SQLiteExtensionLoader {
    
    /**
     * Gets the path to the sqlite-vector extension library.
     * Extracts from JAR if bundled.
     * @return Path to vector extension (.so/.dylib)
     */
    String getVectorExtensionPath();
    
    /**
     * Gets the path to the sqlite-graph extension library.
     * Extracts from JAR if bundled.
     * @return Path to graph extension (.so/.dylib)
     */
    String getGraphExtensionPath();
    
    /**
     * Loads both extensions into a connection.
     * @param conn JDBC Connection
     * @throws SQLException if extension loading fails
     */
    void loadAllExtensions(Connection conn) throws SQLException;
    
    /**
     * Checks if extensions are available for current platform.
     * @return true if extensions can be loaded
     */
    boolean areExtensionsAvailable();
}
```

---

## 7. Schema Migration Contract

### SQLiteSchemaMigrator

```java
/**
 * Handles SQLite schema migrations on startup.
 */
@ApplicationScoped  
public class SQLiteSchemaMigrator {
    
    /**
     * Gets current schema version from database.
     * @return Current version number, 0 if not initialized
     */
    int getCurrentVersion(Connection conn);
    
    /**
     * Applies all pending migrations.
     * @param conn Database connection
     * @throws SQLException if migration fails
     */
    void migrateToLatest(Connection conn);
    
    /**
     * List of all available migrations.
     */
    List<Migration> getMigrations();
    
    /**
     * Migration interface.
     */
    interface Migration {
        int getVersion();
        String getDescription();
        void apply(Connection conn) throws SQLException;
    }
}
```

---

## 8. Error Handling

### SQLite-Specific Exceptions

```java
/**
 * Exception thrown when SQLite extension loading fails.
 */
public class SQLiteExtensionLoadException extends RuntimeException {
    private final String extensionName;
    private final String platform;
}

/**
 * Exception thrown when SQLite database is locked.
 */
public class SQLiteDatabaseLockedException extends RuntimeException {
    private final Duration waitTime;
}

/**
 * Exception thrown when graph query fails.
 */
public class SQLiteGraphQueryException extends RuntimeException {
    private final String cypherQuery;
}
```

---

## 9. Testing Contracts

### Storage Test Interface

```java
/**
 * Base interface for storage implementation tests.
 * All storage tests must pass for both PostgreSQL and SQLite.
 */
public interface StorageTestContract {
    
    // Entity Tests
    void testUpsertEntity();
    void testGetEntity();
    void testDeleteEntity();
    void testEntityProjectIsolation();
    
    // Relation Tests
    void testUpsertRelation();
    void testGetRelationsForEntity();
    void testDeleteRelation();
    
    // Vector Tests
    void testVectorUpsert();
    void testVectorSimilaritySearch();
    void testVectorProjectIsolation();
    
    // Traversal Tests
    void testGraphTraversal();
    void testBFSWithMaxNodes();
    void testShortestPath();
}
```
