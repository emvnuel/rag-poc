# Research: SQLite Storage Port

**Feature Branch**: `009-sqlite-storage-port`  
**Created**: 2024-12-13  
**Status**: Complete

## Research Summary

This document captures research findings for implementing a SQLite port of the RAG-SaaS storage layer using the recommended libraries: **sqlite-graph** and **sqlite-vector**.

---

## 1. Library Analysis: sqlite-graph

**Repository**: https://github.com/agentflare-ai/sqlite-graph  
**Version**: 0.1.0-alpha.0 (Alpha)  
**License**: MIT  
**Stars**: 184

### Decision: Use sqlite-graph for graph storage

### Rationale

sqlite-graph is a SQLite extension providing graph database capabilities with Cypher query support. Key benefits:

1. **Cypher Query Language**: Supports CREATE, MATCH, WHERE, RETURN - similar to Apache AGE syntax used in current PostgreSQL implementation
2. **Virtual Table Interface**: Uses SQLite virtual tables (`CREATE VIRTUAL TABLE graph USING graph()`) with backing tables for nodes and edges
3. **Graph Algorithms**: Built-in connectivity checks, density calculation, degree centrality - matches our traversal requirements
4. **Native Extension**: Pure C99 with no external dependencies, can be loaded via JDBC

### Key Features

| Feature | Status | Notes |
|---------|--------|-------|
| CREATE nodes/edges | ✅ 70/70 TCK tests | Full property support |
| MATCH pattern matching | ✅ | Labels and relationships |
| WHERE filtering | ✅ | Comparison operators |
| RETURN results | ✅ | Nodes, relationships, multiple items |
| Bidirectional relationships | 🚧 v0.2.0 | Coming Q1 2026 |
| Variable-length paths | 🚧 v0.2.0 | `[r*1..3]` syntax |
| Aggregations | 🚧 v0.2.0 | COUNT, SUM, AVG |

### Performance (Alpha)

- Node creation: 300K+ nodes/sec
- Edge creation: 390K+ edges/sec
- Connectivity check: <1ms for 1,000 node graphs
- Pattern matching: 180K nodes/sec with WHERE filtering

### Schema

The extension creates backing tables:
```sql
graph_nodes(id INTEGER, properties TEXT, labels TEXT)
graph_edges(id INTEGER, source INTEGER, target INTEGER, edge_type TEXT, properties TEXT)
```

### Java Integration

- Load via JDBC: `conn.createStatement().execute("SELECT load_extension('/path/to/libgraph.so')")`
- Requires SQLite JDBC driver with extension loading enabled
- xerial sqlite-jdbc supports `enable_load_extension` via connection property

### Alternatives Considered

1. **Relational tables with recursive CTEs**: Lower performance for complex traversals, requires manual implementation of graph algorithms
2. **No graph extension (pure SQL)**: Would require reimplementing all graph operations - significant effort
3. **Apache AGE for SQLite**: Doesn't exist - AGE is PostgreSQL-only

### Risk Assessment

- **Alpha status**: API may change before 1.0, but current API is stable for our use cases
- **Missing features**: Variable-length paths not available until v0.2.0, can work around with recursive CTEs for now
- **Platform support**: Linux x86_64 fully tested, macOS/Windows limited

---

## 2. Library Analysis: sqlite-vector

**Repository**: https://github.com/sqliteai/sqlite-vector  
**Version**: 0.9.52 (Stable)  
**License**: Elastic License 2.0  
**Stars**: 441

### Decision: Use sqlite-vector for vector storage

### Rationale

sqlite-vector is a production-ready SQLite extension for vector similarity search. Key benefits:

1. **No Preindexing Required**: Instant vector search without index-building phases
2. **Quantization Support**: Memory-efficient search with >0.95 recall
3. **SIMD Acceleration**: Optimized for SSE2, AVX2, NEON
4. **Cross-Platform**: Pre-built binaries for Linux, macOS, Windows, Android, iOS
5. **Java Bindings**: Available via Maven Central (`ai.sqlite:vector`)

### Key Features

| Feature | Description |
|---------|-------------|
| Vector types | Float32, Float16, BFloat16, Int8, UInt8 |
| Distance metrics | L2, Squared_L2, Cosine, Dot, L1 |
| Memory usage | 30MB default, configurable |
| Quantization | >0.95 recall with <50MB RAM for 1M vectors |
| Search methods | Full scan (exact), Quantized scan (approximate) |

### API Functions

```sql
-- Initialize vector column
SELECT vector_init('table', 'column', 'type=FLOAT32,dimension=384,distance=COSINE');

-- Insert vectors
INSERT INTO table(embedding) VALUES (vector_as_f32('[0.1, 0.2, 0.3, ...]'));

-- Quantize for fast search
SELECT vector_quantize('table', 'column');
SELECT vector_quantize_preload('table', 'column');

-- Search (returns rowid, distance)
SELECT rowid, distance 
FROM vector_quantize_scan('table', 'column', ?, 20);
```

### Performance

- Handles 1M vectors (768 dimensions) in milliseconds
- Uses <50MB RAM
- Achieves >0.95 recall

### Java Integration

Maven dependency available:
```xml
<dependency>
  <groupId>ai.sqlite</groupId>
  <artifactId>vector</artifactId>
  <version>0.9.52</version>
</dependency>
```

### License Considerations

Elastic License 2.0 allows:
- Non-production use freely
- Production use requires commercial license for managed services

**Decision**: Acceptable for our use case (embedded database, not a managed service offering)

### Alternatives Considered

1. **Application-side cosine similarity**: Lower performance, no SIMD acceleration, loads all vectors into memory
2. **sqlite-vss**: Similar functionality but less active development
3. **Custom SQLite extension**: Significant development effort

---

## 3. Java JDBC Integration Strategy

### Decision: Use xerial sqlite-jdbc with extension loading

### Rationale

The xerial sqlite-jdbc driver is the most widely used SQLite JDBC driver and supports loading native extensions.

### Implementation Approach

```java
// Enable extension loading
SQLiteConfig config = new SQLiteConfig();
config.enableLoadExtension(true);

Connection conn = DriverManager.getConnection(
    "jdbc:sqlite:/path/to/database.db", 
    config.toProperties()
);

// Load extensions
Statement stmt = conn.createStatement();
stmt.execute("SELECT load_extension('/path/to/vector')");
stmt.execute("SELECT load_extension('/path/to/libgraph')");
```

### Extension Distribution

**Options**:
1. **Bundled in JAR**: Include native libraries for all platforms in resources
2. **External path**: Configure extension path via application properties
3. **Auto-extract**: Extract from JAR to temp directory at runtime

**Decision**: Use option 3 (auto-extract) for ease of deployment, with fallback to option 2 for custom deployments.

### Quarkus Integration

- Use `@Startup` bean to initialize extensions on application start
- Use `@IfBuildProperty` to conditionally enable SQLite vs PostgreSQL
- Implement CDI producers for storage implementations

---

## 4. Project Isolation Strategy

### Decision: Use project_id column filtering (same as PostgreSQL)

For **VectorStorage** and **ExtractionCacheStorage**: 
- Add `project_id` column to all tables
- All queries include `WHERE project_id = ?`

For **GraphStorage**:
- **Option A**: Separate virtual table per project (`graph_{projectId}`)
- **Option B**: Single graph with `project_id` in node/edge properties

**Decision**: Option B - Single graph table with project_id filtering
- Simplifies connection management
- Matches sqlite-vector approach
- Requires adding project_id to graph queries

---

## 5. Concurrency Model

### Decision: Single writer, multiple readers with WAL mode

### Rationale

SQLite's WAL (Write-Ahead Logging) mode allows:
- One writer at a time
- Multiple concurrent readers
- Readers don't block writers and vice versa

### Implementation

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA cache_size = -64000; -- 64MB cache
PRAGMA busy_timeout = 30000; -- 30 second timeout
```

### Write Serialization

Use Java-level locking for write operations:
```java
private final ReentrantLock writeLock = new ReentrantLock();

public void upsertEntity(Entity entity) {
    writeLock.lock();
    try {
        // perform write
    } finally {
        writeLock.unlock();
    }
}
```

---

## 6. Schema Migration Strategy

### Decision: Version-based migration with startup validation

### Implementation

1. Store schema version in dedicated table:
```sql
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT DEFAULT (datetime('now'))
);
```

2. On startup, check version and apply migrations:
```java
int currentVersion = getSchemaVersion();
for (Migration m : MIGRATIONS) {
    if (m.version > currentVersion) {
        m.apply(connection);
        updateSchemaVersion(m.version);
    }
}
```

---

## 7. Feature Parity Analysis

### GraphStorage Methods

| Method | Implementation |
|--------|----------------|
| `upsertEntity` | Cypher MERGE via sqlite-graph |
| `getEntity` | Cypher MATCH with property filter |
| `getAllEntities` | Cypher MATCH (n:Entity) |
| `deleteEntity` | Cypher MATCH + DELETE |
| `upsertRelation` | Cypher MERGE for edges |
| `getRelationsForEntity` | Cypher MATCH pattern |
| `traverse` | Cypher path pattern or recursive CTE |
| `traverseBFS` | Recursive CTE with level tracking |
| `findShortestPath` | Cypher shortestPath or recursive CTE |

### VectorStorage Methods

| Method | Implementation |
|--------|----------------|
| `upsert` | INSERT OR REPLACE with vector_as_f32 |
| `upsertBatch` | Transaction with multiple INSERTs |
| `query` | vector_quantize_scan with JOIN |
| `get` | SELECT by id |
| `delete` | DELETE by id |
| `deleteByProject` | DELETE WHERE project_id = ? |

---

## 8. Open Questions (Resolved)

### Q1: How to handle sqlite-graph alpha status?

**Resolution**: Proceed with sqlite-graph. The alpha API is stable for our use cases (CREATE, MATCH, WHERE, RETURN). Missing features (variable-length paths) can be worked around with recursive CTEs until v0.2.0.

### Q2: How to distribute native extensions?

**Resolution**: Auto-extract approach. Bundle platform-specific binaries in resources, extract to temp directory at runtime, load via JDBC. Provide configuration option for custom paths.

### Q3: Elastic License compatibility?

**Resolution**: Acceptable. We're building an embedded RAG application, not a managed database service. No commercial license required for our use case.

---

## 9. Dependencies

### Maven Dependencies

```xml
<!-- SQLite JDBC Driver -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.0.0</version>
</dependency>

<!-- sqlite-vector Java bindings -->
<dependency>
    <groupId>ai.sqlite</groupId>
    <artifactId>vector</artifactId>
    <version>0.9.52</version>
</dependency>
```

### Native Libraries

| Library | Platform | Source |
|---------|----------|--------|
| libgraph.so | Linux x86_64 | sqlite-graph releases |
| libgraph.dylib | macOS | sqlite-graph releases |
| vector.so | Linux x86_64 | sqlite-vector releases |
| vector.dylib | macOS | sqlite-vector releases |

---

## 10. Summary of Decisions

| Topic | Decision | Rationale |
|-------|----------|-----------|
| Graph storage | sqlite-graph | Cypher support, graph algorithms |
| Vector storage | sqlite-vector | Production-ready, quantization, SIMD |
| JDBC driver | xerial sqlite-jdbc | Extension loading support |
| Extension distribution | Auto-extract from JAR | Ease of deployment |
| Project isolation | Column filtering | Matches PostgreSQL approach |
| Concurrency | WAL mode + write lock | Single writer, multiple readers |
| Schema migration | Version-based startup check | Automatic migrations |
