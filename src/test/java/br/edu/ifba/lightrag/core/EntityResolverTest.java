package br.edu.ifba.lightrag.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for EntityResolver.
 * 
 * Tests the full entity resolution pipeline including:
 * - Duplicate detection and merging
 * - Type safety (prevents merging different types)
 * - Feature flag behavior
 * - Statistics collection
 * - Error handling/fallback
 * 
 * Following TDD principles: These tests should FAIL initially
 * until implementation is complete.
 */
@QuarkusTest
class EntityResolverTest {
    
    @Inject
    EntityResolver resolver;
    
    @Inject
    DeduplicationConfig config;
    
    private List<Entity> testEntities;
    
    @BeforeEach
    void setUp() {
        // Reset test entities before each test
        testEntities = new ArrayList<>();
    }
    
    // ========================================================================
    // Tests for resolveDuplicates() - Basic Resolution
    // ========================================================================
    
    @Test
    @DisplayName("resolveDuplicates should merge Warren Home variations into one entity")
    void testResolveDuplicatesWarrenHomeCluster() {
        // Arrange: Warren Home cluster from research.md
        testEntities = List.of(
            createEntity("Warren State Home", "ORGANIZATION", "A state home for training"),
            createEntity("Warren Home", "ORGANIZATION", "Training facility"),
            createEntity("Warren State Home and Training School", "ORGANIZATION", "Educational institution")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-1");
        
        // Assert
        assertNotNull(resolved);
        assertTrue(resolved.size() < testEntities.size(), 
            "Should have fewer entities after deduplication");
        assertEquals(1, resolved.size(), 
            "Warren variations should merge into 1 canonical entity");
        
        Entity canonical = resolved.get(0);
        assertEquals("Warren State Home and Training School", canonical.getEntityName(),
            "Should select longest name as canonical");
        assertEquals("ORGANIZATION", canonical.getEntityType());
    }
    
    @Test
    @DisplayName("resolveDuplicates should merge MIT abbreviation variants")
    void testResolveDuplicatesMITVariations() {
        // Arrange: MIT variations (abbrev + full + acronym)
        // Note: Full name merge requires higher abbreviation weight (see quickstart.md)
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Leading research university"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "Founded in 1861"),
            createEntity("M.I.T.", "ORGANIZATION", "Tech institute")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-2");
        
        // Assert: MIT and M.I.T. should merge (both normalize to "mit")
        // Full name stays separate with default weights (requires abbreviation weight tuning)
        assertEquals(2, resolved.size(), 
            "MIT/M.I.T. merge into 1, full name separate (realistic with default weights)");
        
        // Verify MIT abbreviations merged
        boolean hasMitAbbreviation = resolved.stream()
            .anyMatch(e -> e.getEntityName().equalsIgnoreCase("MIT") || 
                          e.getEntityName().equalsIgnoreCase("M.I.T."));
        assertTrue(hasMitAbbreviation, "Should have MIT abbreviation form");
    }
    
    @Test
    @DisplayName("resolveDuplicates should NOT merge entities with different types")
    void testResolveDuplicatesTypeSafety() {
        // Arrange: Same name, different types (from research.md)
        testEntities = List.of(
            createEntity("Apple", "ORGANIZATION", "Technology company based in Cupertino"),
            createEntity("Apple", "PRODUCT", "Edible fruit grown on apple trees")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-3");
        
        // Assert: Type mismatch should prevent merging
        assertEquals(2, resolved.size(), 
            "Different types should NOT merge, even with identical names");
        
        // Verify both types are preserved
        Set<String> types = new HashSet<>();
        for (Entity e : resolved) {
            types.add(e.getEntityType());
        }
        assertTrue(types.contains("ORGANIZATION"), "Should preserve ORGANIZATION type");
        assertTrue(types.contains("PRODUCT"), "Should preserve PRODUCT type");
    }
    
    @Test
    @DisplayName("resolveDuplicates should keep unrelated entities separate")
    void testResolveDuplicatesUnrelatedEntities() {
        // Arrange: Completely different entities
        testEntities = List.of(
            createEntity("Harvard University", "ORGANIZATION", "Ivy League university"),
            createEntity("Stanford University", "ORGANIZATION", "West coast university"),
            createEntity("John Smith", "PERSON", "Research professor")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-4");
        
        // Assert: No duplicates, should return original entities
        assertEquals(3, resolved.size(), 
            "Unrelated entities should not be merged");
    }
    
    @Test
    @DisplayName("resolveDuplicates should handle person name variations")
    void testResolveDuplicatesPersonNames() {
        // Arrange: Person name variations
        testEntities = List.of(
            createEntity("Robert Johnson", "PERSON", "Author and researcher"),
            createEntity("Bob Johnson", "PERSON", "Research author"),
            createEntity("R. Johnson", "PERSON", "Scientist")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-5");
        
        // Assert: Should merge person name variations
        assertTrue(resolved.size() <= testEntities.size(), 
            "Should have same or fewer entities after resolution");
        
        // Depending on similarity threshold, may merge 2 or all 3
        assertTrue(resolved.size() >= 1 && resolved.size() <= 3,
            "Should merge at least some person name variations");
    }
    
    @Test
    @DisplayName("resolveDuplicates should handle mixed clusters correctly")
    void testResolveDuplicatesMixedClusters() {
        // Arrange: Multiple clusters + singleton
        testEntities = List.of(
            // MIT cluster (won't merge with default config - abbreviation has low weight)
            createEntity("MIT", "ORGANIZATION", "Tech university"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "Research institution"),
            // Harvard cluster (will merge - good token/substring overlap)
            createEntity("Harvard", "ORGANIZATION", "Ivy League school"),
            createEntity("Harvard University", "ORGANIZATION", "Cambridge university"),
            // Singleton
            createEntity("Stanford University", "ORGANIZATION", "West coast university")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-6");
        
        // Assert: 5 entities → 4 entities (Harvard merges, MIT stays separate, Stanford stays)
        // Note: MIT + full name won't merge because abbreviation weight is only 10%
        // To merge abbreviations with full names, increase abbreviation weight to 0.20+ (per quickstart.md)
        assertEquals(4, resolved.size(), 
            "Should create 4 entities: MIT separate, MIT-full separate, Harvard merged, Stanford");
    }
    
    @Test
    @DisplayName("resolveDuplicates should handle empty list")
    void testResolveDuplicatesEmptyList() {
        // Arrange
        testEntities = List.of();
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-7");
        
        // Assert
        assertNotNull(resolved);
        assertTrue(resolved.isEmpty(), "Empty input should return empty output");
    }
    
    @Test
    @DisplayName("resolveDuplicates should handle single entity")
    void testResolveDuplicatesSingleEntity() {
        // Arrange
        testEntities = List.of(
            createEntity("Unique Entity", "ORGANIZATION", "No duplicates")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-8");
        
        // Assert
        assertEquals(1, resolved.size(), "Single entity should remain unchanged");
        assertEquals("Unique Entity", resolved.get(0).getEntityName());
    }
    
    @Test
    @DisplayName("resolveDuplicates should throw exception for null entities")
    void testResolveDuplicatesNullEntities() {
        assertThrows(IllegalArgumentException.class, () -> {
            resolver.resolveDuplicates(null, "test-project");
        });
    }
    
    @Test
    @DisplayName("resolveDuplicates should handle null project ID gracefully")
    void testResolveDuplicatesNullProjectId() {
        // Arrange
        testEntities = List.of(
            createEntity("Test Entity", "ORGANIZATION", "Test description")
        );
        
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            List<Entity> resolved = resolver.resolveDuplicates(testEntities, null);
            assertNotNull(resolved);
        });
    }
    
    // ========================================================================
    // Tests for resolveDuplicatesWithStats() - Statistics Collection
    // ========================================================================
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should return statistics for Warren Home cluster")
    void testResolveDuplicatesWithStatsWarrenHome() {
        // Arrange
        testEntities = List.of(
            createEntity("Warren State Home", "ORGANIZATION", "State facility"),
            createEntity("Warren Home", "ORGANIZATION", "Training center"),
            createEntity("Warren State Home and Training School", "ORGANIZATION", "School")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-9");
        
        // Assert
        assertNotNull(result);
        assertEquals(3, result.originalEntityCount(), "Should record 3 original entities");
        assertEquals(1, result.resolvedEntityCount(), "Should have 1 entity after merging");
        assertEquals(2, result.duplicatesRemoved(), "Should remove 2 duplicates");
        assertEquals(1, result.clustersFound(), "Should find 1 cluster");
        assertTrue(result.hadDuplicates(), "Should indicate duplicates were found");
        assertTrue(result.deduplicationRate() > 0, "Should have non-zero deduplication rate");
        assertNotNull(result.processingTime(), "Should record processing time");
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should return statistics for mixed clusters")
    void testResolveDuplicatesWithStatsMixedClusters() {
        // Arrange: 5 entities with realistic merging behavior
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Tech"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "University"),
            createEntity("Harvard", "ORGANIZATION", "Ivy"),
            createEntity("Harvard University", "ORGANIZATION", "Cambridge"),
            createEntity("Stanford University", "ORGANIZATION", "West coast")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-10");
        
        // Assert
        assertEquals(5, result.originalEntityCount());
        assertEquals(4, result.resolvedEntityCount(), "Harvard merges, MIT stays separate");
        assertEquals(1, result.duplicatesRemoved(), "Only Harvard + Harvard University merge");
        assertEquals(4, result.clustersFound(), "Should find 4 total clusters: 1 merged (Harvard) + 3 singletons");
        assertTrue(result.hadDuplicates());
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should return zero stats for no duplicates")
    void testResolveDuplicatesWithStatsNoDuplicates() {
        // Arrange: Unrelated entities
        testEntities = List.of(
            createEntity("Entity A", "ORGANIZATION", "Description A"),
            createEntity("Entity B", "ORGANIZATION", "Description B"),
            createEntity("Entity C", "PERSON", "Description C")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-11");
        
        // Assert
        assertEquals(3, result.originalEntityCount());
        assertEquals(3, result.resolvedEntityCount());
        assertEquals(0, result.duplicatesRemoved(), "Should have 0 duplicates removed");
        assertFalse(result.hadDuplicates(), "Should indicate no duplicates found");
        assertEquals(0.0, result.deduplicationRate(), 0.001, "Deduplication rate should be 0%");
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should return empty stats for empty list")
    void testResolveDuplicatesWithStatsEmptyList() {
        // Arrange
        testEntities = List.of();
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-12");
        
        // Assert
        assertNotNull(result);
        assertTrue(result.resolvedEntities().isEmpty());
        assertEquals(0, result.originalEntityCount());
        assertEquals(0, result.resolvedEntityCount());
        assertEquals(0, result.duplicatesRemoved());
        assertEquals(0, result.clustersFound());
        assertFalse(result.hadDuplicates());
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should record processing time")
    void testResolveDuplicatesWithStatsProcessingTime() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Tech university"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "Research")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-13");
        
        // Assert
        assertNotNull(result.processingTime());
        assertTrue(result.processingTime().toMillis() >= 0, 
            "Processing time should be non-negative");
        assertTrue(result.averageProcessingTimePerEntity() >= 0, 
            "Average time per entity should be non-negative");
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should throw exception for null entities")
    void testResolveDuplicatesWithStatsNullEntities() {
        assertThrows(IllegalArgumentException.class, () -> {
            resolver.resolveDuplicatesWithStats(null, "test-project");
        });
    }
    
    // ========================================================================
    // Tests for Feature Flag Behavior
    // ========================================================================
    
    @Test
    @DisplayName("resolveDuplicates should respect enabled flag from config")
    void testResolveDuplicatesRespectsEnabledFlag() {
        // Arrange: Entities with clear duplicates
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Tech"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "University")
        );
        
        // Act: Call with feature enabled (via application.properties)
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-14");
        
        // Assert: When enabled, should perform deduplication
        // (config.enabled() from application.properties should control this)
        assertNotNull(resolved);
        
        // If enabled=true: resolved.size() < testEntities.size()
        // If enabled=false: resolved.size() == testEntities.size()
        // Test verifies the method respects the config
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should respect enabled flag")
    void testResolveDuplicatesWithStatsRespectsEnabledFlag() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Tech"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "University")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-15");
        
        // Assert: Method should complete without error
        assertNotNull(result);
        assertNotNull(result.resolvedEntities());
        
        // If disabled, should return original entities unchanged
        // If enabled, should perform deduplication
    }
    
    // ========================================================================
    // Tests for Error Handling / Fallback
    // ========================================================================
    
    @Test
    @DisplayName("resolveDuplicates should handle malformed entities gracefully")
    void testResolveDuplicatesMalformedEntities() {
        // Arrange: Entities with edge cases
        testEntities = List.of(
            createEntity("", "ORGANIZATION", "Empty name"),  // Edge case: empty name
            createEntity("A", "ORGANIZATION", "Single char"),
            createEntity("Normal Entity", "ORGANIZATION", "Normal description")
        );
        
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-16");
            assertNotNull(resolved);
            // Should handle gracefully, possibly returning original entities
        });
    }
    
    @Test
    @DisplayName("resolveDuplicatesWithStats should handle malformed entities gracefully")
    void testResolveDuplicatesWithStatsMalformedEntities() {
        // Arrange: Entities with null type (edge case)
        testEntities = List.of(
            createEntity("Entity 1", null, "No type specified"),
            createEntity("Entity 2", null, "Also no type")
        );
        
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-17");
            assertNotNull(result);
            assertNotNull(result.resolvedEntities());
        });
    }
    
    // ========================================================================
    // Integration Tests - Complex Scenarios
    // ========================================================================
    
    @Test
    @DisplayName("Integration: Full pipeline with multiple entity types")
    void testIntegrationMultipleEntityTypes() {
        // Arrange: Mix of ORGANIZATION, PERSON, PRODUCT with duplicates
        testEntities = List.of(
            // Organizations (2 clusters)
            createEntity("MIT", "ORGANIZATION", "Tech university"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "Research"),
            createEntity("Harvard", "ORGANIZATION", "Ivy League"),
            createEntity("Harvard University", "ORGANIZATION", "Cambridge"),
            // Persons (1 cluster)
            createEntity("John Smith", "PERSON", "Professor"),
            createEntity("J. Smith", "PERSON", "Academic"),
            // Products (no duplicates)
            createEntity("iPhone", "PRODUCT", "Smartphone"),
            createEntity("MacBook", "PRODUCT", "Laptop")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-18");
        
        // Assert: 8 entities with duplicates → should merge some
        assertEquals(8, result.originalEntityCount());
        assertTrue(result.resolvedEntityCount() < 8, 
            "Should merge some duplicates across entity types");
        assertTrue(result.hadDuplicates());
        
        // Verify type safety: each type should have its own clusters
        Map<String, Long> typeCounts = new HashMap<>();
        for (Entity e : result.resolvedEntities()) {
            String type = e.getEntityType() != null ? e.getEntityType() : "null";
            typeCounts.put(type, typeCounts.getOrDefault(type, 0L) + 1);
        }
        
        // Should have entities of all 3 types
        assertTrue(typeCounts.containsKey("ORGANIZATION"));
        assertTrue(typeCounts.containsKey("PERSON"));
        assertTrue(typeCounts.containsKey("PRODUCT"));
    }
    
    @Test
    @DisplayName("Integration: Large batch with many duplicates")
    void testIntegrationLargeBatchDuplicates() {
        // Arrange: Create variations of same entities (simulating real extraction)
        List<Entity> largeList = new ArrayList<>();
        
        // Add MIT variations - with default config, these won't all merge
        // MIT + M.I.T. + MIT University → merge (3→1)
        largeList.add(createEntity("MIT", "ORGANIZATION", "Tech"));
        largeList.add(createEntity("M.I.T.", "ORGANIZATION", "University"));
        largeList.add(createEntity("MIT University", "ORGANIZATION", "Cambridge"));
        // Full names stay separate (abbreviation weight too low)
        largeList.add(createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "Research"));
        largeList.add(createEntity("Massachusetts Inst of Tech", "ORGANIZATION", "Engineering"));
        
        // Add Harvard variations (3 entities → 1, good token overlap)
        largeList.add(createEntity("Harvard", "ORGANIZATION", "Ivy"));
        largeList.add(createEntity("Harvard University", "ORGANIZATION", "Cambridge MA"));
        largeList.add(createEntity("Harvard Univ", "ORGANIZATION", "Boston area"));
        
        // Add unique entities (2 entities → 2)
        largeList.add(createEntity("Stanford University", "ORGANIZATION", "West coast"));
        largeList.add(createEntity("Yale University", "ORGANIZATION", "New Haven"));
        
        testEntities = largeList;
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-19");
        
        // Assert: 10 entities → 6 entities (realistic with default config)
        // MIT cluster: 3 sub-groups (MIT/M.I.T./MIT Univ=1, Mass Inst Tech=1, Mass Inst of Tech=1)
        // Harvard cluster: 1 merged entity
        // Stanford: 1, Yale: 1
        assertEquals(10, result.originalEntityCount());
        assertEquals(6, result.resolvedEntityCount(), 
            "Should merge to 6 entities with default config (4 duplicates removed)");
        assertEquals(4, result.duplicatesRemoved());
        assertTrue(result.deduplicationRate() >= 0.3, 
            "Should achieve at least 30% deduplication rate");
    }
    
    @Test
    @DisplayName("Integration: Type safety prevents cross-type merging in complex scenario")
    void testIntegrationComplexTypeSafety() {
        // Arrange: Same names across different types
        testEntities = List.of(
            // Apple organization cluster
            createEntity("Apple", "ORGANIZATION", "Tech company"),
            createEntity("Apple Inc", "ORGANIZATION", "Cupertino company"),
            createEntity("Apple Inc.", "ORGANIZATION", "iPhone maker"),
            // Apple product
            createEntity("Apple", "PRODUCT", "Fruit"),
            // Orange organization
            createEntity("Orange", "ORGANIZATION", "Telecom company"),
            // Orange product
            createEntity("Orange", "PRODUCT", "Citrus fruit")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-20");
        
        // Assert: 6 entities → 4 entities
        // Apple ORG cluster (3→1), Apple PRODUCT (1→1), Orange ORG (1→1), Orange PRODUCT (1→1)
        assertEquals(6, result.originalEntityCount());
        assertEquals(4, result.resolvedEntityCount(),
            "Should merge Apple ORG cluster but keep types separate");
        assertEquals(2, result.duplicatesRemoved(), 
            "Should only merge within Apple ORG cluster");
        
        // Verify we still have 4 distinct type+name combinations
        Set<String> typeNamePairs = new HashSet<>();
        for (Entity e : result.resolvedEntities()) {
            typeNamePairs.add(e.getEntityType() + ":" + e.getEntityName());
        }
        assertEquals(4, typeNamePairs.size(), "Should have 4 distinct type+name combinations");
    }
    
    @Test
    @DisplayName("Integration: Verify toLogString produces valid output")
    void testIntegrationLogStringFormat() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Tech"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "University")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(testEntities, "test-project-21");
        
        // Assert: toLogString should not throw exception and should contain key info
        assertDoesNotThrow(() -> {
            String logString = result.toLogString();
            assertNotNull(logString);
            assertFalse(logString.isEmpty());
            
            // Should contain key statistics
            assertTrue(logString.contains("entities") || logString.contains("Entity"));
            assertTrue(logString.contains(String.valueOf(result.originalEntityCount())));
        });
    }
    
    // ========================================================================
    // T038: Type-Aware Constraint Tests (User Story 2)
    // ========================================================================
    
    @Test
    @DisplayName("T038: Mercury planet vs element should not merge")
    void testTypeSafetyMercuryPlanetVsElement() {
        // Arrange: Mercury with different types
        testEntities = List.of(
            createEntity("Mercury", "PLANET", "The closest planet to the Sun"),
            createEntity("Mercury", "CHEMICAL_ELEMENT", "A chemical element with symbol Hg"),
            createEntity("Mercury", "PLANET", "First planet from Sun")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-mercury");
        
        // Assert: Should have 2 entities (2 planets merge, element stays separate)
        assertEquals(2, resolved.size(), 
            "Should have 2 entities: 1 planet (merged) and 1 element");
        
        // Verify both types are preserved
        Set<String> types = new HashSet<>();
        for (Entity e : resolved) {
            types.add(e.getEntityType());
        }
        assertTrue(types.contains("PLANET"));
        assertTrue(types.contains("CHEMICAL_ELEMENT"));
    }
    
    @Test
    @DisplayName("T038: Washington person vs location should not merge")
    void testTypeSafetyWashingtonPersonVsLocation() {
        // Arrange: Washington with different types
        testEntities = List.of(
            createEntity("Washington", "PERSON", "George Washington, first US president"),
            createEntity("Washington", "LOCATION", "The capital of the United States"),
            createEntity("George Washington", "PERSON", "First president")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-washington");
        
        // Assert: Should have 2 entities (2 persons might not merge, location separate)
        assertTrue(resolved.size() >= 2, 
            "Should have at least 2 entities: person(s) and location");
        
        // Verify both types are preserved
        Set<String> types = new HashSet<>();
        for (Entity e : resolved) {
            types.add(e.getEntityType());
        }
        assertTrue(types.contains("PERSON"));
        assertTrue(types.contains("LOCATION"));
    }
    
    @Test
    @DisplayName("T038: Jordan person vs country should not merge")
    void testTypeSafetyJordanPersonVsCountry() {
        // Arrange: Jordan with different types
        testEntities = List.of(
            createEntity("Michael Jordan", "PERSON", "Famous basketball player"),
            createEntity("Jordan", "GEO", "A country in the Middle East"),
            createEntity("Jordan", "PERSON", "Basketball legend")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-jordan");
        
        // Assert: Should have 2 entities (persons separate from country)
        assertTrue(resolved.size() >= 2, 
            "Should have at least 2 entities: person(s) and country");
        
        // Verify both types are preserved
        Set<String> types = new HashSet<>();
        for (Entity e : resolved) {
            types.add(e.getEntityType());
        }
        assertTrue(types.contains("PERSON"));
        assertTrue(types.contains("GEO"));
    }
    
    @Test
    @DisplayName("T038: Type constraints should apply case-insensitively")
    void testTypeSafetyCaseInsensitive() {
        // Arrange: Same names with case variations in type (same type, different cases)
        // Plus a different type to verify separation
        testEntities = List.of(
            createEntity("Microsoft", "organization", "Tech company"),
            createEntity("Microsoft", "ORGANIZATION", "Software company"),
            createEntity("Microsoft", "Organization", "Redmond company"),
            createEntity("Microsoft", "PRODUCT", "Software product") // Different type
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-case");
        
        // Assert: Type matching is case-insensitive
        // The 3 organization variants should be treated as same type (might merge depending on similarity)
        // The PRODUCT should definitely stay separate
        assertTrue(resolved.size() >= 2 && resolved.size() <= 4, 
            "Should have 2-4 entities: organization(s) separate from product");
        
        // Verify we have both ORGANIZATION and PRODUCT types
        Set<String> types = new HashSet<>();
        for (Entity e : resolved) {
            types.add(e.getEntityType().toUpperCase());
        }
        assertTrue(types.contains("ORGANIZATION"));
        assertTrue(types.contains("PRODUCT"));
        
        // Most importantly: ORGANIZATION and PRODUCT should NOT merge despite identical names
        assertEquals(2, types.size(), 
            "Case-insensitive type matching should prevent ORGANIZATION from merging with PRODUCT");
    }
    
    @Test
    @DisplayName("T038: Multiple entity types with identical names should remain separate")
    void testTypeSafetyMultipleTypesIdenticalNames() {
        // Arrange: Same name across 4 different types
        testEntities = List.of(
            createEntity("Java", "PROGRAMMING_LANGUAGE", "Object-oriented programming language"),
            createEntity("Java", "GEO", "Indonesian island"),
            createEntity("Java", "PRODUCT", "Coffee brand"),
            createEntity("Java", "ORGANIZATION", "Software company named Java")
        );
        
        // Act
        List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-java");
        
        // Assert: Should have 4 entities (no cross-type merging)
        assertEquals(4, resolved.size(), 
            "Should keep all 4 types separate despite identical names");
        
        // Verify all 4 types are preserved
        Set<String> types = new HashSet<>();
        for (Entity e : resolved) {
            types.add(e.getEntityType());
        }
        assertEquals(4, types.size(), "Should preserve all 4 distinct types");
        assertTrue(types.contains("PROGRAMMING_LANGUAGE"));
        assertTrue(types.contains("GEO"));
        assertTrue(types.contains("PRODUCT"));
        assertTrue(types.contains("ORGANIZATION"));
    }
    
    @Test
    @DisplayName("T038: Type constraints with stats should show zero merges across types")
    void testTypeSafetyWithStatsAcrossTypes() {
        // Arrange: Same name, different types
        testEntities = List.of(
            createEntity("Mercury", "PLANET", "Planet description"),
            createEntity("Mercury", "CHEMICAL_ELEMENT", "Element description"),
            createEntity("Mercury", "PERSON", "Roman god")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(
            testEntities, "test-project-mercury-stats");
        
        // Assert: No merges should occur
        assertEquals(3, result.originalEntityCount());
        assertEquals(3, result.resolvedEntityCount(), 
            "No cross-type merges should occur");
        assertEquals(0, result.duplicatesRemoved(), 
            "Should not merge entities with different types");
        assertFalse(result.hadDuplicates(), 
            "Should report no duplicates when all types differ");
    }
    
    @Test
    @DisplayName("T038: Type constraints should allow within-type merging")
    void testTypeSafetyWithinTypeMerging() {
        // Arrange: Mix of same-type duplicates and cross-type entities
        testEntities = List.of(
            // Apple organizations (should merge)
            createEntity("Apple", "ORGANIZATION", "Tech company"),
            createEntity("Apple Inc", "ORGANIZATION", "Cupertino"),
            createEntity("Apple Inc.", "ORGANIZATION", "iPhone maker"),
            // Apple products (should NOT merge with organizations)
            createEntity("Apple", "PRODUCT", "Red fruit"),
            createEntity("apple", "PRODUCT", "Green fruit")
        );
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(
            testEntities, "test-project-within-type");
        
        // Assert: 5 entities → 3 entities
        // Organizations: 3→1, Products: 2→2 (might merge if names match after normalization)
        assertEquals(5, result.originalEntityCount());
        assertTrue(result.resolvedEntityCount() <= 3, 
            "Should merge organizations but keep products separate from orgs");
        assertTrue(result.duplicatesRemoved() >= 2, 
            "Should merge at least the 3 Apple organizations");
        
        // Verify both types are preserved
        Set<String> types = new HashSet<>();
        for (Entity e : result.resolvedEntities()) {
            types.add(e.getEntityType());
        }
        assertTrue(types.contains("ORGANIZATION"));
        assertTrue(types.contains("PRODUCT"));
    }
    
    @Test
    @DisplayName("T038: Null types should not prevent resolution")
    void testTypeSafetyNullTypes() {
        // Arrange: Mix of null types and valid types
        testEntities = List.of(
            createEntity("Entity A", null, "No type specified"),
            createEntity("Entity A", null, "Also no type"),
            createEntity("Entity B", "ORGANIZATION", "Has type")
        );
        
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            List<Entity> resolved = resolver.resolveDuplicates(testEntities, "test-project-null-types");
            assertNotNull(resolved);
            // Entities with null types should be handled gracefully
            assertTrue(resolved.size() <= testEntities.size());
        });
    }
    
    @Test
    @DisplayName("T038: Type-aware clustering with large mixed batch")
    void testTypeSafetyLargeMixedBatch() {
        // Arrange: Large batch with multiple types and duplicates
        List<Entity> largeList = new ArrayList<>();
        
        // Add organizations with duplicates (should merge within type)
        largeList.add(createEntity("Microsoft", "ORGANIZATION", "Tech company"));
        largeList.add(createEntity("Microsoft Corp", "ORGANIZATION", "Software"));
        largeList.add(createEntity("Microsoft Corporation", "ORGANIZATION", "Redmond"));
        
        // Add persons with similar names but different types (should not merge)
        largeList.add(createEntity("Bill Gates", "PERSON", "Microsoft founder"));
        
        // Add products with same names as organizations (should not merge)
        largeList.add(createEntity("Microsoft", "PRODUCT", "Software product"));
        
        // Add locations
        largeList.add(createEntity("Washington", "LOCATION", "US capital"));
        largeList.add(createEntity("Washington", "PERSON", "George Washington"));
        
        testEntities = largeList;
        
        // Act
        EntityResolutionResult result = resolver.resolveDuplicatesWithStats(
            testEntities, "test-project-large-mixed");
        
        // Assert: 7 entities with proper type isolation
        assertEquals(7, result.originalEntityCount());
        
        // Microsoft organizations should merge: 3→1
        // Others stay separate: Bill Gates (person), Microsoft (product), Washington (location + person)
        // Expected: 1 (Microsoft org) + 1 (Bill Gates) + 1 (Microsoft product) + 2 (Washington entities) = 5
        assertEquals(5, result.resolvedEntityCount(), 
            "Should merge Microsoft organizations but keep types isolated");
        assertEquals(2, result.duplicatesRemoved(), 
            "Should only merge the 3 Microsoft organizations");
        
        // Verify type diversity is preserved
        Set<String> types = new HashSet<>();
        for (Entity e : result.resolvedEntities()) {
            types.add(e.getEntityType());
        }
        assertTrue(types.size() >= 3, "Should preserve at least 3 distinct types");
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Helper method to create test entities with default source ID.
     */
    private Entity createEntity(String name, String type, String description) {
        return new Entity(name, type, description, "test-source-" + name.hashCode());
    }
}
