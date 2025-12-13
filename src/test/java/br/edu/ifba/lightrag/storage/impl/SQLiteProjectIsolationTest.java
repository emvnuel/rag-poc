package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;

/**
 * Unit tests verifying project isolation in SQLite storage.
 * 
 * <p>Tests ensure that multiple projects in the same SQLite database
 * are completely isolated - queries from one project never return
 * data from another project.</p>
 * 
 * @since spec-009
 */
class SQLiteProjectIsolationTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;
    private SQLiteExtractionCacheStorage cacheStorage;
    private SQLiteKVStorage kvStorage;

    private String projectA;
    private String projectB;
    private String documentA;
    private String documentB;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("isolation-test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize storages
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        cacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
        cacheStorage.initialize().join();
        
        kvStorage = new SQLiteKVStorage(connectionManager);
        kvStorage.initialize().join();
        
        // Create two projects
        projectA = UUID.randomUUID().toString();
        projectB = UUID.randomUUID().toString();
        documentA = UUID.randomUUID().toString();
        documentB = UUID.randomUUID().toString();
        
        createProject(projectA, "Project A");
        createProject(projectB, "Project B");
        createDocument(documentA, projectA);
        createDocument(documentB, projectB);
        
        graphStorage.createProjectGraph(projectA).join();
        graphStorage.createProjectGraph(projectB).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (kvStorage != null) kvStorage.close();
        if (cacheStorage != null) cacheStorage.close();
        if (graphStorage != null) graphStorage.close();
        if (vectorStorage != null) vectorStorage.close();
        if (connectionManager != null) connectionManager.close();
    }

    // ========================================================================
    // Vector Storage Isolation Tests
    // ========================================================================

    @Nested
    @DisplayName("Vector Storage Isolation")
    class VectorStorageIsolation {

        @Test
        @DisplayName("Vectors from project A should not appear in project B queries")
        void testVectorQueryIsolation() {
            // Insert vectors into project A
            List<VectorEntry> entriesA = createVectorEntries(projectA, documentA, 10);
            vectorStorage.upsertBatch(entriesA).join();
            
            // Insert vectors into project B
            List<VectorEntry> entriesB = createVectorEntries(projectB, documentB, 5);
            vectorStorage.upsertBatch(entriesB).join();
            
            // Query project A - should only get project A vectors
            float[] queryVector = createTestVector(384, 0.5f);
            VectorFilter filterA = new VectorFilter(null, null, projectA);
            var resultsA = vectorStorage.query(queryVector, 20, filterA).join();
            
            assertEquals(10, resultsA.size(), "Project A should have 10 vectors");
            for (var result : resultsA) {
                assertEquals(projectA, result.metadata().projectId(), 
                    "All results should belong to project A");
            }
            
            // Query project B - should only get project B vectors
            VectorFilter filterB = new VectorFilter(null, null, projectB);
            var resultsB = vectorStorage.query(queryVector, 20, filterB).join();
            
            assertEquals(5, resultsB.size(), "Project B should have 5 vectors");
            for (var result : resultsB) {
                assertEquals(projectB, result.metadata().projectId(), 
                    "All results should belong to project B");
            }
        }

        @Test
        @DisplayName("Deleting vectors in project A should not affect project B")
        void testVectorDeleteIsolation() {
            // Insert vectors into both projects
            List<VectorEntry> entriesA = createVectorEntries(projectA, documentA, 10);
            List<VectorEntry> entriesB = createVectorEntries(projectB, documentB, 10);
            vectorStorage.upsertBatch(entriesA).join();
            vectorStorage.upsertBatch(entriesB).join();
            
            // Delete all vectors from project A by IDs
            List<String> idsA = entriesA.stream().map(VectorEntry::id).toList();
            vectorStorage.deleteBatch(idsA).join();
            
            // Project A should have no vectors
            VectorFilter filterA = new VectorFilter(null, null, projectA);
            var resultsA = vectorStorage.query(createTestVector(384, 0.5f), 20, filterA).join();
            assertEquals(0, resultsA.size(), "Project A should have no vectors after delete");
            
            // Project B should still have all vectors
            VectorFilter filterB = new VectorFilter(null, null, projectB);
            var resultsB = vectorStorage.query(createTestVector(384, 0.5f), 20, filterB).join();
            assertEquals(10, resultsB.size(), "Project B should still have 10 vectors");
        }

        @Test
        @DisplayName("getChunkIdsByDocumentId should only return chunks from the specified project")
        void testGetChunkIdsByDocumentIdIsolation() {
            // Insert vectors into both projects
            List<VectorEntry> entriesA = createVectorEntries(projectA, documentA, 5);
            List<VectorEntry> entriesB = createVectorEntries(projectB, documentB, 5);
            vectorStorage.upsertBatch(entriesA).join();
            vectorStorage.upsertBatch(entriesB).join();
            
            // Get chunk IDs for project A's document
            var chunkIdsA = vectorStorage.getChunkIdsByDocumentId(projectA, documentA).join();
            assertEquals(5, chunkIdsA.size(), "Should find 5 chunks for document A");
            
            // Get chunk IDs for project B's document
            var chunkIdsB = vectorStorage.getChunkIdsByDocumentId(projectB, documentB).join();
            assertEquals(5, chunkIdsB.size(), "Should find 5 chunks for document B");
            
            // Cross-project query should return empty
            var crossQuery = vectorStorage.getChunkIdsByDocumentId(projectA, documentB).join();
            assertEquals(0, crossQuery.size(), "Cross-project query should return no results");
        }

        @Test
        @DisplayName("deleteEntityEmbeddings should only affect the specified project")
        void testDeleteEntityEmbeddingsIsolation() {
            // Insert entity embeddings into both projects
            List<VectorEntry> entriesA = createEntityEmbeddings(projectA, documentA, 
                List.of("EntityA1", "EntityA2", "EntityA3"));
            List<VectorEntry> entriesB = createEntityEmbeddings(projectB, documentB, 
                List.of("EntityB1", "EntityB2"));
            vectorStorage.upsertBatch(entriesA).join();
            vectorStorage.upsertBatch(entriesB).join();
            
            // Delete entity embeddings from project A
            int deleted = vectorStorage.deleteEntityEmbeddings(projectA, 
                Set.of("EntityA1", "EntityA2")).join();
            assertEquals(2, deleted, "Should delete 2 entity embeddings from project A");
            
            // Project B entity embeddings should be unaffected
            VectorFilter filterB = new VectorFilter("entity", null, projectB);
            var resultsB = vectorStorage.query(createTestVector(384, 0.5f), 10, filterB).join();
            assertEquals(2, resultsB.size(), "Project B should still have 2 entity embeddings");
        }
    }

    // ========================================================================
    // Graph Storage Isolation Tests
    // ========================================================================

    @Nested
    @DisplayName("Graph Storage Isolation")
    class GraphStorageIsolation {

        @Test
        @DisplayName("Entities from project A should not appear in project B queries")
        void testEntityQueryIsolation() {
            // Insert entities into project A
            List<Entity> entitiesA = createEntities("A", 5);
            graphStorage.upsertEntities(projectA, entitiesA).join();
            
            // Insert entities into project B
            List<Entity> entitiesB = createEntities("B", 3);
            graphStorage.upsertEntities(projectB, entitiesB).join();
            
            // Query project A
            var resultA = graphStorage.getAllEntities(projectA).join();
            assertEquals(5, resultA.size(), "Project A should have 5 entities");
            for (var entity : resultA) {
                assertTrue(entity.getEntityName().contains("a"), 
                    "All entities should be from project A");
            }
            
            // Query project B
            var resultB = graphStorage.getAllEntities(projectB).join();
            assertEquals(3, resultB.size(), "Project B should have 3 entities");
            for (var entity : resultB) {
                assertTrue(entity.getEntityName().contains("b"), 
                    "All entities should be from project B");
            }
        }

        @Test
        @DisplayName("Relations from project A should not appear in project B queries")
        void testRelationQueryIsolation() {
            // Insert entities and relations into project A
            List<Entity> entitiesA = createEntities("A", 3);
            graphStorage.upsertEntities(projectA, entitiesA).join();
            List<Relation> relationsA = createRelations("A", 2);
            graphStorage.upsertRelations(projectA, relationsA).join();
            
            // Insert entities and relations into project B
            List<Entity> entitiesB = createEntities("B", 3);
            graphStorage.upsertEntities(projectB, entitiesB).join();
            List<Relation> relationsB = createRelations("B", 4);
            graphStorage.upsertRelations(projectB, relationsB).join();
            
            // Query project A relations
            var resultA = graphStorage.getAllRelations(projectA).join();
            assertEquals(2, resultA.size(), "Project A should have 2 relations");
            
            // Query project B relations
            var resultB = graphStorage.getAllRelations(projectB).join();
            assertEquals(4, resultB.size(), "Project B should have 4 relations");
        }

        @Test
        @DisplayName("Graph traversal should not cross project boundaries")
        void testTraversalIsolation() {
            // Create connected graph in project A
            List<Entity> entitiesA = List.of(
                Entity.builder().entityName("HubA").entityType("CONCEPT")
                    .description("Hub in A").addSourceChunkId("chunkA").build(),
                Entity.builder().entityName("NodeA1").entityType("CONCEPT")
                    .description("Node 1 in A").addSourceChunkId("chunkA").build(),
                Entity.builder().entityName("NodeA2").entityType("CONCEPT")
                    .description("Node 2 in A").addSourceChunkId("chunkA").build()
            );
            graphStorage.upsertEntities(projectA, entitiesA).join();
            
            List<Relation> relationsA = List.of(
                Relation.builder().srcId("HubA").tgtId("NodeA1")
                    .description("connects").keywords("link").weight(1.0)
                    .addSourceChunkId("chunkA").build(),
                Relation.builder().srcId("HubA").tgtId("NodeA2")
                    .description("connects").keywords("link").weight(1.0)
                    .addSourceChunkId("chunkA").build()
            );
            graphStorage.upsertRelations(projectA, relationsA).join();
            
            // Create connected graph in project B
            List<Entity> entitiesB = List.of(
                Entity.builder().entityName("HubB").entityType("CONCEPT")
                    .description("Hub in B").addSourceChunkId("chunkB").build(),
                Entity.builder().entityName("NodeB1").entityType("CONCEPT")
                    .description("Node 1 in B").addSourceChunkId("chunkB").build()
            );
            graphStorage.upsertEntities(projectB, entitiesB).join();
            
            List<Relation> relationsB = List.of(
                Relation.builder().srcId("HubB").tgtId("NodeB1")
                    .description("connects").keywords("link").weight(1.0)
                    .addSourceChunkId("chunkB").build()
            );
            graphStorage.upsertRelations(projectB, relationsB).join();
            
            // Traverse from HubA - should only find nodes in project A
            var subgraphA = graphStorage.traverse(projectA, "huba", 2).join();
            assertNotNull(subgraphA);
            assertTrue(subgraphA.entities().size() >= 1, "Should find HubA and neighbors");
            for (var entity : subgraphA.entities()) {
                assertFalse(entity.getEntityName().toLowerCase().contains("hubb"), 
                    "Should not find any project B entities");
            }
            
            // Traverse from HubB - should only find nodes in project B
            var subgraphB = graphStorage.traverse(projectB, "hubb", 2).join();
            assertNotNull(subgraphB);
            assertTrue(subgraphB.entities().size() >= 1, "Should find HubB and neighbors");
            for (var entity : subgraphB.entities()) {
                assertFalse(entity.getEntityName().toLowerCase().contains("huba"), 
                    "Should not find any project A entities");
            }
        }

        @Test
        @DisplayName("Deleting entities in project A should not affect project B")
        void testEntityDeleteIsolation() {
            // Insert entities into both projects
            List<Entity> entitiesA = createEntities("A", 5);
            List<Entity> entitiesB = createEntities("B", 5);
            graphStorage.upsertEntities(projectA, entitiesA).join();
            graphStorage.upsertEntities(projectB, entitiesB).join();
            
            // Delete entities from project A
            Set<String> toDelete = Set.of("entitya0", "entitya1", "entitya2");
            graphStorage.deleteEntities(projectA, toDelete).join();
            
            // Project A should have 2 entities left
            var resultA = graphStorage.getAllEntities(projectA).join();
            assertEquals(2, resultA.size(), "Project A should have 2 entities after delete");
            
            // Project B should still have all 5 entities
            var resultB = graphStorage.getAllEntities(projectB).join();
            assertEquals(5, resultB.size(), "Project B should still have 5 entities");
        }

        @Test
        @DisplayName("getEntity should only find entities in the specified project")
        void testGetEntityIsolation() {
            // Insert same-named entity into both projects
            Entity entityA = Entity.builder()
                .entityName("SharedName")
                .entityType("CONCEPT")
                .description("Entity in project A")
                .addSourceChunkId("chunkA")
                .build();
            Entity entityB = Entity.builder()
                .entityName("SharedName")
                .entityType("CONCEPT")
                .description("Entity in project B")
                .addSourceChunkId("chunkB")
                .build();
            
            graphStorage.upsertEntities(projectA, List.of(entityA)).join();
            graphStorage.upsertEntities(projectB, List.of(entityB)).join();
            
            // Get entity from project A
            var foundA = graphStorage.getEntity(projectA, "sharedname").join();
            assertNotNull(foundA, "Should find entity in project A");
            assertTrue(foundA.getDescription().contains("project A"), 
                "Should be project A's entity");
            
            // Get entity from project B
            var foundB = graphStorage.getEntity(projectB, "sharedname").join();
            assertNotNull(foundB, "Should find entity in project B");
            assertTrue(foundB.getDescription().contains("project B"), 
                "Should be project B's entity");
        }
    }

    // ========================================================================
    // Extraction Cache Isolation Tests
    // ========================================================================

    @Nested
    @DisplayName("Extraction Cache Isolation")
    class ExtractionCacheIsolation {

        @Test
        @DisplayName("Cache entries from project A should not appear in project B")
        void testCacheIsolation() {
            // Use proper UUIDs for chunk IDs
            String chunkA1 = UUID.randomUUID().toString();
            String chunkA2 = UUID.randomUUID().toString();
            String chunkB1 = UUID.randomUUID().toString();
            
            // Store cache entries in project A
            String hashA1 = "hash_a1";
            String hashA2 = "hash_a2";
            cacheStorage.store(projectA, CacheType.ENTITY_EXTRACTION, chunkA1, hashA1, 
                "extraction result A1", 100).join();
            cacheStorage.store(projectA, CacheType.ENTITY_EXTRACTION, chunkA2, hashA2, 
                "extraction result A2", 100).join();
            
            // Store cache entries in project B with same hash
            cacheStorage.store(projectB, CacheType.ENTITY_EXTRACTION, chunkB1, hashA1, 
                "extraction result B1", 100).join();
            
            // Retrieve from project A
            var resultA1 = cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, hashA1).join();
            assertTrue(resultA1.isPresent(), "Should find cache in project A");
            assertEquals("extraction result A1", resultA1.get().result());
            
            // Retrieve from project B - same hash but different project
            var resultB1 = cacheStorage.get(projectB, CacheType.ENTITY_EXTRACTION, hashA1).join();
            assertTrue(resultB1.isPresent(), "Should find cache in project B");
            assertEquals("extraction result B1", resultB1.get().result());
            
            // Cross-project query with non-existent hash should return empty
            var crossResult = cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, "nonexistent").join();
            assertTrue(crossResult.isEmpty(), "Non-existent hash should return empty");
        }

        @Test
        @DisplayName("Deleting cache in project A should not affect project B")
        void testCacheDeleteIsolation() {
            String hash = "shared_hash";
            
            // Use proper UUIDs for chunk IDs
            String chunkA = UUID.randomUUID().toString();
            String chunkB = UUID.randomUUID().toString();
            
            // Store in both projects with same hash
            cacheStorage.store(projectA, CacheType.ENTITY_EXTRACTION, chunkA, hash, "A1", 100).join();
            cacheStorage.store(projectB, CacheType.ENTITY_EXTRACTION, chunkB, hash, "B1", 100).join();
            
            // Delete from project A
            cacheStorage.deleteByProject(projectA).join();
            
            // Project A cache should be gone
            var resultA = cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, hash).join();
            assertTrue(resultA.isEmpty(), "Project A cache should be deleted");
            
            // Project B cache should still exist
            var resultB = cacheStorage.get(projectB, CacheType.ENTITY_EXTRACTION, hash).join();
            assertTrue(resultB.isPresent(), "Project B cache should still exist");
            assertEquals("B1", resultB.get().result());
        }
    }

    // ========================================================================
    // KV Storage Isolation Tests
    // ========================================================================

    @Nested
    @DisplayName("KV Storage Isolation")
    class KVStorageIsolation {

        @Test
        @DisplayName("KV entries should be isolated by key prefix")
        void testKVKeyPrefixIsolation() {
            String keyA1 = "project:" + projectA + ":key1";
            String keyA2 = "project:" + projectA + ":key2";
            String keyB1 = "project:" + projectB + ":key1";
            
            // Store with project-specific keys
            kvStorage.set(keyA1, "value A1").join();
            kvStorage.set(keyA2, "value A2").join();
            kvStorage.set(keyB1, "value B1").join();
            
            // Retrieve
            var resultA1 = kvStorage.get(keyA1).join();
            assertEquals("value A1", resultA1);
            
            var resultB1 = kvStorage.get(keyB1).join();
            assertEquals("value B1", resultB1);
            
            // List keys with pattern for project A
            var keysA = kvStorage.keys("project:" + projectA + ":*").join();
            assertEquals(2, keysA.size(), "Project A should have 2 keys");
            
            // List keys with pattern for project B
            var keysB = kvStorage.keys("project:" + projectB + ":*").join();
            assertEquals(1, keysB.size(), "Project B should have 1 key");
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void createProject(String projId, String name) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO projects (id, name, created_at, updated_at) " +
                "VALUES (?, ?, datetime('now'), datetime('now'))")) {
            stmt.setString(1, projId);
            stmt.setString(2, name);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    private void createDocument(String docId, String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO documents (id, project_id, type, status, created_at, updated_at) " +
                "VALUES (?, ?, 'TXT', 'PROCESSED', datetime('now'), datetime('now'))")) {
            stmt.setString(1, docId);
            stmt.setString(2, projId);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    private List<VectorEntry> createVectorEntries(String projectId, String documentId, int count) {
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            float[] vector = createTestVector(384, (float) i / count);
            VectorMetadata metadata = new VectorMetadata(
                "chunk", "Content " + i, documentId, i, projectId
            );
            entries.add(new VectorEntry(id, vector, metadata));
        }
        return entries;
    }

    private List<VectorEntry> createEntityEmbeddings(String projectId, String documentId, 
            List<String> entityNames) {
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < entityNames.size(); i++) {
            String id = UUID.randomUUID().toString();
            float[] vector = createTestVector(384, (float) i / entityNames.size());
            VectorMetadata metadata = new VectorMetadata(
                "entity", entityNames.get(i), documentId, null, projectId
            );
            entries.add(new VectorEntry(id, vector, metadata));
        }
        return entries;
    }

    private float[] createTestVector(int dimension, float baseValue) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = baseValue + (i * 0.001f);
        }
        // Normalize
        float norm = 0;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) vector[i] /= norm;
        }
        return vector;
    }

    private List<Entity> createEntities(String prefix, int count) {
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entities.add(Entity.builder()
                .entityName("Entity" + prefix + i)
                .entityType("CONCEPT")
                .description("Description for entity " + prefix + i)
                .addSourceChunkId("chunk" + prefix)
                .build());
        }
        return entities;
    }

    private List<Relation> createRelations(String prefix, int count) {
        List<Relation> relations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            relations.add(Relation.builder()
                .srcId("Entity" + prefix + i)
                .tgtId("Entity" + prefix + ((i + 1) % count))
                .description("Relation " + i)
                .keywords("test")
                .weight(1.0)
                .addSourceChunkId("chunk" + prefix)
                .build());
        }
        return relations;
    }
}
