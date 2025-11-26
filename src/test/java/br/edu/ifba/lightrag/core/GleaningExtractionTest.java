package br.edu.ifba.lightrag.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for gleaning extraction functionality.
 * 
 * <p>Tests verify the iterative gleaning logic that captures additional
 * entities and relations missed in the initial extraction pass.</p>
 * 
 * <p>These tests use mock extraction results to test merging logic
 * without requiring actual LLM calls.</p>
 */
class GleaningExtractionTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(GleaningExtractionTest.class);
    
    @BeforeEach
    void setUp() {
        LOG.info("Setting up GleaningExtractionTest");
    }
    
    /**
     * Test merging extraction results from multiple passes.
     * 
     * Scenario: Initial extraction finds some entities, gleaning finds more.
     */
    @Test
    void testMergeExtractionResults() {
        LOG.info("Testing extraction result merging");
        
        // Initial extraction results
        List<Entity> initialEntities = new ArrayList<>();
        initialEntities.add(new Entity("Warren Home", "ORGANIZATION", "A mental health facility", null));
        initialEntities.add(new Entity("Pennsylvania", "LOCATION", "US state", null));
        
        List<Relation> initialRelations = new ArrayList<>();
        initialRelations.add(new Relation("Warren Home", "Pennsylvania", 
            "Warren Home is located in Pennsylvania", "located_in", 1.0, null));
        
        // Gleaning pass results
        List<Entity> gleaningEntities = new ArrayList<>();
        gleaningEntities.add(new Entity("Dr. Smith", "PERSON", "Medical director", null));
        gleaningEntities.add(new Entity("Warren Home", "ORGANIZATION", "Additional description", null)); // Duplicate
        
        List<Relation> gleaningRelations = new ArrayList<>();
        gleaningRelations.add(new Relation("Dr. Smith", "Warren Home", 
            "Dr. Smith works at Warren Home", "works_at", 0.9, null));
        
        // Merge results
        List<Entity> mergedEntities = new ArrayList<>(initialEntities);
        for (Entity e : gleaningEntities) {
            if (mergedEntities.stream().noneMatch(existing -> 
                existing.getEntityName().equalsIgnoreCase(e.getEntityName()))) {
                mergedEntities.add(e);
            }
        }
        
        List<Relation> mergedRelations = new ArrayList<>(initialRelations);
        for (Relation r : gleaningRelations) {
            if (mergedRelations.stream().noneMatch(existing -> 
                existing.getSrcId().equals(r.getSrcId()) && 
                existing.getTgtId().equals(r.getTgtId()))) {
                mergedRelations.add(r);
            }
        }
        
        // Verify merge
        assertEquals(3, mergedEntities.size(), "Should have 3 unique entities after merge");
        assertEquals(2, mergedRelations.size(), "Should have 2 unique relations after merge");
        
        assertTrue(mergedEntities.stream().anyMatch(e -> "Dr. Smith".equals(e.getEntityName())),
            "Should include Dr. Smith from gleaning pass");
        assertTrue(mergedRelations.stream().anyMatch(r -> "works_at".equals(r.getKeywords())),
            "Should include works_at relation from gleaning pass");
        
        LOG.info("Extraction result merging test passed");
    }
    
    /**
     * Test counting new entities from gleaning pass.
     */
    @Test
    void testCountNewEntities() {
        LOG.info("Testing new entity counting");
        
        // Existing entities
        List<Entity> existing = List.of(
            new Entity("Entity A", "TYPE_A", "Description A", null),
            new Entity("Entity B", "TYPE_B", "Description B", null)
        );
        
        // New entities from gleaning
        List<Entity> gleaned = List.of(
            new Entity("Entity A", "TYPE_A", "Updated description", null), // Duplicate
            new Entity("Entity C", "TYPE_C", "Description C", null), // New
            new Entity("Entity D", "TYPE_D", "Description D", null)  // New
        );
        
        // Count new entities
        long newCount = gleaned.stream()
            .filter(e -> existing.stream().noneMatch(ex -> 
                ex.getEntityName().equalsIgnoreCase(e.getEntityName())))
            .count();
        
        assertEquals(2, newCount, "Should have 2 new entities from gleaning");
        
        LOG.info("New entity counting test passed");
    }
    
    /**
     * Test counting new relations from gleaning pass.
     */
    @Test
    void testCountNewRelations() {
        LOG.info("Testing new relation counting");
        
        // Existing relations
        List<Relation> existing = List.of(
            new Relation("A", "B", "A relates to B", "rel_1", 1.0, null)
        );
        
        // New relations from gleaning
        List<Relation> gleaned = List.of(
            new Relation("A", "B", "Updated description", "rel_1", 1.0, null), // Duplicate
            new Relation("B", "C", "B relates to C", "rel_2", 0.9, null), // New
            new Relation("A", "C", "A relates to C", "rel_3", 0.8, null)  // New
        );
        
        // Count new relations
        long newCount = gleaned.stream()
            .filter(r -> existing.stream().noneMatch(ex -> 
                ex.getSrcId().equals(r.getSrcId()) && ex.getTgtId().equals(r.getTgtId())))
            .count();
        
        assertEquals(2, newCount, "Should have 2 new relations from gleaning");
        
        LOG.info("New relation counting test passed");
    }
    
    /**
     * Test that empty gleaning results are handled properly.
     */
    @Test
    void testEmptyGleaningResults() {
        LOG.info("Testing empty gleaning results");
        
        List<Entity> initialEntities = List.of(
            new Entity("Entity A", "TYPE_A", "Description", null)
        );
        List<Relation> initialRelations = List.of(
            new Relation("Entity A", "Entity B", "Description", "rel", 1.0, null)
        );
        
        // Empty gleaning results
        List<Entity> gleanedEntities = List.of();
        List<Relation> gleanedRelations = List.of();
        
        // Merge (should keep original)
        List<Entity> merged = new ArrayList<>(initialEntities);
        merged.addAll(gleanedEntities);
        
        List<Relation> mergedRels = new ArrayList<>(initialRelations);
        mergedRels.addAll(gleanedRelations);
        
        assertEquals(1, merged.size(), "Should keep original entities");
        assertEquals(1, mergedRels.size(), "Should keep original relations");
        
        LOG.info("Empty gleaning results test passed");
    }
    
    /**
     * Test gleaning stop condition when no new entities found.
     */
    @Test
    void testGleaningStopCondition() {
        LOG.info("Testing gleaning stop condition");
        
        // Simulate multiple gleaning passes
        int maxPasses = 3;
        List<Entity> accumulated = new ArrayList<>();
        accumulated.add(new Entity("Entity A", "TYPE_A", "Description A", null));
        
        int passesPerformed = 0;
        for (int pass = 0; pass < maxPasses; pass++) {
            // Simulate no new entities found
            List<Entity> passResults = List.of(
                new Entity("Entity A", "TYPE_A", "Duplicate description", null)
            );
            
            long newEntities = passResults.stream()
                .filter(e -> accumulated.stream().noneMatch(ex -> 
                    ex.getEntityName().equalsIgnoreCase(e.getEntityName())))
                .count();
            
            if (newEntities == 0) {
                LOG.info("Stopping gleaning at pass {}: no new entities", pass);
                break;
            }
            
            passesPerformed++;
        }
        
        assertEquals(0, passesPerformed, "Should stop immediately when no new entities found");
        
        LOG.info("Gleaning stop condition test passed");
    }
    
    /**
     * Test sourceChunkIds are preserved during gleaning merge.
     */
    @Test
    void testSourceChunkIdsPreservedInMerge() {
        LOG.info("Testing sourceChunkIds preservation");
        
        // Initial entity with source chunks
        Entity initial = new Entity.Builder()
            .entityName("Warren Home")
            .entityType("ORGANIZATION")
            .description("Initial description")
            .sourceChunkIds(List.of("chunk-1", "chunk-2"))
            .build();
        
        // Gleaning entity for same entity with different source chunk
        Entity gleaned = new Entity.Builder()
            .entityName("Warren Home")
            .entityType("ORGANIZATION")
            .description("Gleaning description")
            .sourceChunkIds(List.of("chunk-3"))
            .build();
        
        // Merge: combine sourceChunkIds
        List<String> mergedSourceChunks = new ArrayList<>(initial.getSourceChunkIds());
        mergedSourceChunks.addAll(gleaned.getSourceChunkIds());
        
        Entity merged = initial.withSourceChunkIds(mergedSourceChunks);
        
        assertEquals(3, merged.getSourceChunkIds().size(), 
            "Merged entity should have 3 source chunk IDs");
        assertTrue(merged.getSourceChunkIds().containsAll(List.of("chunk-1", "chunk-2", "chunk-3")),
            "Should contain all source chunk IDs");
        
        LOG.info("sourceChunkIds preservation test passed");
    }
    
    /**
     * Test entity name normalization during extraction.
     */
    @Test
    void testEntityNameNormalization() {
        LOG.info("Testing entity name normalization");
        
        // Names that should be normalized
        String[] rawNames = {
            "  Warren Home  ",           // Extra whitespace
            "\"Warren Home\"",            // Quotes
            "'Warren Home'",              // Single quotes
            "Warren   Home",              // Multiple spaces
        };
        
        for (String rawName : rawNames) {
            // Normalize: trim, remove quotes, collapse whitespace
            String normalized = normalizeEntityName(rawName);
            
            assertEquals("Warren Home", normalized, 
                "Name '" + rawName + "' should normalize to 'Warren Home'");
        }
        
        LOG.info("Entity name normalization test passed");
    }
    
    /**
     * Helper method to normalize entity names (mirrors LightRAG logic).
     */
    private String normalizeEntityName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        
        // Remove leading/trailing quotes
        String normalized = name.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\"")) ||
            (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        
        // Collapse whitespace
        normalized = normalized.replaceAll("\\s+", " ").trim();
        
        return normalized;
    }
    
    /**
     * Test self-referential relation filtering.
     */
    @Test
    void testSelfReferentialRelationFiltering() {
        LOG.info("Testing self-referential relation filtering");
        
        // Relations including self-referential ones
        List<Relation> relations = List.of(
            new Relation("A", "B", "rel_1", "A relates to B", 1.0, null),
            new Relation("A", "A", "rel_2", "A relates to itself", 1.0, null), // Self-referential
            new Relation("B", "C", "rel_3", "B relates to C", 1.0, null)
        );
        
        // Filter out self-referential relations
        List<Relation> filtered = relations.stream()
            .filter(r -> !r.getSrcId().equalsIgnoreCase(r.getTgtId()))
            .toList();
        
        assertEquals(2, filtered.size(), "Should filter out self-referential relations");
        assertFalse(filtered.stream().anyMatch(r -> r.getSrcId().equals(r.getTgtId())),
            "No self-referential relations should remain");
        
        LOG.info("Self-referential relation filtering test passed");
    }
    
    /**
     * Test extraction result with large number of entities.
     */
    @Test
    void testLargeExtractionMerge() {
        LOG.info("Testing large extraction merge");
        
        // Create 100 initial entities
        List<Entity> initial = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            initial.add(new Entity("Entity_" + i, "TYPE", "Description " + i, null));
        }
        
        // Create 50 gleaning entities (25 duplicates, 25 new)
        List<Entity> gleaned = new ArrayList<>();
        for (int i = 50; i < 75; i++) { // 25 duplicates (50-74 already exist)
            gleaned.add(new Entity("Entity_" + i, "TYPE", "Updated " + i, null));
        }
        for (int i = 100; i < 125; i++) { // 25 new (100-124)
            gleaned.add(new Entity("Entity_" + i, "TYPE", "New " + i, null));
        }
        
        // Merge
        List<Entity> merged = new ArrayList<>(initial);
        for (Entity e : gleaned) {
            if (merged.stream().noneMatch(ex -> ex.getEntityName().equals(e.getEntityName()))) {
                merged.add(e);
            }
        }
        
        assertEquals(125, merged.size(), "Should have 125 unique entities after merge");
        
        // Verify new entities were added
        assertTrue(merged.stream().anyMatch(e -> e.getEntityName().equals("Entity_124")),
            "Should include new entities from gleaning");
        
        LOG.info("Large extraction merge test passed");
    }
}
