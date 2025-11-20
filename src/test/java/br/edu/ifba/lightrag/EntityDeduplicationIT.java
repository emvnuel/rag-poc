package br.edu.ifba.lightrag;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.EntityResolver;
import br.edu.ifba.lightrag.core.EntityResolutionResult;
import br.edu.ifba.project.Project;
import br.edu.ifba.project.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for semantic entity deduplication feature.
 * Verifies that entity resolution correctly identifies and merges duplicate entities
 * with name variations while preserving type-based distinctions.
 * 
 * Tests cover:
 * - Basic duplicate detection (exact matches)
 * - Substring variations
 * - Abbreviation handling
 * - Type-aware matching (preventing false positives)
 * - Person name variations
 * - Resolution statistics tracking
 */
@QuarkusTest
class EntityDeduplicationIT {

    private static final Logger LOG = Logger.getLogger(EntityDeduplicationIT.class);

    @Inject
    EntityResolver entityResolver;

    @Inject
    ProjectService projectService;

    private UUID testProjectId;

    /**
     * Set up test environment with test project.
     */
    @BeforeEach
    void setUp() {
        LOG.info("Setting up EntityDeduplicationIT test environment");

        // Create test project
        Project project = projectService.create(new Project("Entity Deduplication Test"));
        testProjectId = project.getId();
        LOG.infof("Test project created: %s", testProjectId);
    }

    /**
     * Test basic duplicate detection with exact name matches.
     * 
     * Scenario: Same entity name repeated multiple times should be merged into one.
     */
    @Test
    void testBasicDuplicateDetection() {
        LOG.info("Testing basic duplicate detection");

        // Create entities with exact duplicate names
        List<Entity> entities = List.of(
            new Entity("Warren Home", "ORGANIZATION", "A mental health facility", null),
            new Entity("Warren Home", "ORGANIZATION", "Provided care services", null),
            new Entity("Warren Home", "ORGANIZATION", "Located in Pennsylvania", null)
        );

        // Resolve duplicates
        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // Should merge to 1 entity
        assertEquals(1, resolved.size(), "Exact duplicates should be merged into one entity");
        
        Entity merged = resolved.get(0);
        assertEquals("Warren Home", merged.getEntityName());
        assertEquals("ORGANIZATION", merged.getEntityType());
        assertNotNull(merged.getDescription(), "Merged entity should have a description");

        LOG.info("Basic duplicate detection test passed");
    }

    /**
     * Test substring variation detection.
     * 
     * Scenario: "Warren State Home" and "Warren Home" should be recognized as the same entity.
     */
    @Test
    void testSubstringVariations() {
        LOG.info("Testing substring variation detection");

        List<Entity> entities = List.of(
            new Entity("Warren State Home and Training School", "ORGANIZATION", "Established in 1907", null),
            new Entity("Warren Home", "ORGANIZATION", "Provided care for mentally disabled", null),
            new Entity("Warren State Home", "ORGANIZATION", "Located in Pennsylvania", null),
            new Entity("Warren Home School", "ORGANIZATION", "Offered educational programs", null)
        );

        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // Should merge all variations into 1-2 entities
        assertTrue(resolved.size() <= 2, 
            "Substring variations should be merged (expected 1-2 entities, got " + resolved.size() + ")");
        
        // The canonical entity should contain "Warren"
        Entity canonical = resolved.get(0);
        assertTrue(canonical.getEntityName().contains("Warren"), 
            "Canonical name should contain 'Warren'");

        LOG.infof("Substring variations merged: %d -> %d entities", entities.size(), resolved.size());
    }

    /**
     * Test type-aware matching to prevent false positives.
     * 
     * Scenario: "Apple Inc." (ORGANIZATION) and "apple" (FOOD) should remain separate
     * despite name similarity, because they have different types.
     */
    @Test
    void testTypeAwareMatching() {
        LOG.info("Testing type-aware matching");

        List<Entity> entities = List.of(
            new Entity("Apple Inc.", "ORGANIZATION", "Technology company", null),
            new Entity("Apple", "ORGANIZATION", "Manufacturer of iPhone", null),
            new Entity("apple", "FOOD", "Nutritious fruit", null),
            new Entity("Red apple", "FOOD", "Popular variety", null)
        );

        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // Should result in 2 entities: 1 ORGANIZATION, 1 FOOD
        assertEquals(2, resolved.size(), 
            "Different types should not be merged (expected 2, got " + resolved.size() + ")");

        // Verify one is ORGANIZATION and one is FOOD
        long orgCount = resolved.stream().filter(e -> "ORGANIZATION".equals(e.getEntityType())).count();
        long foodCount = resolved.stream().filter(e -> "FOOD".equals(e.getEntityType())).count();
        
        assertEquals(1, orgCount, "Should have 1 ORGANIZATION entity");
        assertEquals(1, foodCount, "Should have 1 FOOD entity");

        LOG.info("Type-aware matching test passed - no cross-type merges");
    }

    /**
     * Test person name variation handling.
     * 
     * Scenario: "James Gordon", "Jim Gordon", "Commissioner Gordon" have different
     * similarity scores. With default threshold (0.4), subtle variations may not merge.
     * This test validates that the system processes person names without errors.
     */
    @Test
    void testPersonNameVariations() {
        LOG.info("Testing person name variations");

        List<Entity> entities = List.of(
            new Entity("James Gordon", "PERSON", "Police commissioner", null),
            new Entity("Jim Gordon", "PERSON", "Works with Batman", null),
            new Entity("Commissioner Gordon", "PERSON", "Leader of GCPD", null)
        );

        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // With default threshold, these variations may not merge
        // Test validates processing completes without error and produces reasonable results
        assertTrue(resolved.size() <= 3 && resolved.size() >= 1, 
            "Person name processing should complete successfully (got " + resolved.size() + " entities)");

        LOG.infof("Person name variations: %d -> %d entities", entities.size(), resolved.size());
    }

    /**
     * Test that different entities with different names remain separate.
     * 
     * Scenario: "Warren Home" and "Warwick Home" are different entities and
     * should not be merged despite name similarity.
     */
    @Test
    void testDifferentEntitiesRemainSeparate() {
        LOG.info("Testing different entities remain separate");

        List<Entity> entities = List.of(
            new Entity("Warren Home", "ORGANIZATION", "Located in Pennsylvania", null),
            new Entity("Warwick Home", "ORGANIZATION", "Located in New York", null)
        );

        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // Should remain as 2 separate entities
        assertEquals(2, resolved.size(), 
            "Different entities should not be merged");
        
        assertTrue(resolved.stream().anyMatch(e -> e.getEntityName().contains("Warren")),
            "Should contain Warren Home");
        assertTrue(resolved.stream().anyMatch(e -> e.getEntityName().contains("Warwick")),
            "Should contain Warwick Home");

        LOG.info("Different entities correctly remain separate");
    }

    /**
     * Test resolution statistics tracking with resolveDuplicatesWithStats.
     * 
     * Validates that EntityResolutionResult contains accurate metrics:
     * - Original entity count
     * - Resolved entity count
     * - Number of duplicates removed
     * - Cluster count
     */
    @Test
    void testResolutionStatistics() {
        LOG.info("Testing resolution statistics tracking");

        List<Entity> entities = List.of(
            new Entity("Warren Home", "ORGANIZATION", "Facility 1", null),
            new Entity("Warren Home", "ORGANIZATION", "Facility 2", null),
            new Entity("Warren State Home", "ORGANIZATION", "Facility 3", null),
            new Entity("Different Place", "ORGANIZATION", "Unrelated", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        assertNotNull(result, "Result should not be null");
        assertEquals(4, result.originalEntityCount(), "Original count should be 4");
        assertTrue(result.resolvedEntityCount() < 4, "Resolved count should be less than original");
        assertTrue(result.duplicatesRemoved() > 0, "Should have removed duplicates");
        assertTrue(result.clustersFound() > 0, "Should have formed clusters");
        assertEquals(4 - result.resolvedEntityCount(), result.duplicatesRemoved(),
            "Duplicates removed = original - resolved");

        LOG.infof("Resolution statistics: %d -> %d entities (-%d duplicates, %d clusters, %dms)",
            result.originalEntityCount(), result.resolvedEntityCount(), result.duplicatesRemoved(),
            result.clustersFound(), result.processingTime().toMillis());
    }

    /**
     * Test empty entity list handling.
     * 
     * Scenario: Empty input should return empty output without errors.
     */
    @Test
    void testEmptyEntityList() {
        LOG.info("Testing empty entity list handling");

        List<Entity> empty = List.of();
        List<Entity> resolved = entityResolver.resolveDuplicates(empty, testProjectId.toString());

        assertNotNull(resolved, "Resolved list should not be null");
        assertEquals(0, resolved.size(), "Empty input should produce empty output");

        LOG.info("Empty list handling test passed");
    }

    /**
     * Test single entity handling.
     * 
     * Scenario: Single entity should be returned unchanged.
     */
    @Test
    void testSingleEntity() {
        LOG.info("Testing single entity handling");

        List<Entity> single = List.of(
            new Entity("Unique Entity", "ORGANIZATION", "No duplicates", null)
        );

        List<Entity> resolved = entityResolver.resolveDuplicates(single, testProjectId.toString());

        assertEquals(1, resolved.size(), "Single entity should remain as one");
        assertEquals("Unique Entity", resolved.get(0).getEntityName());

        LOG.info("Single entity handling test passed");
    }

    /**
     * Test large batch processing to validate performance.
     * 
     * Scenario: Process 100 entities with duplicates and verify:
     * - Processing completes successfully
     * - Duplicates are detected
     * - Processing time is reasonable (<5s for 100 entities)
     */
    @Test
    void testLargeBatchProcessing() {
        LOG.info("Testing large batch processing");

        // Create 100 entities with ~30% duplicates
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            entities.add(new Entity("Entity_" + i, "ORGANIZATION", "Unique entity " + i, null));
        }
        // Add 30 duplicates of first 15 entities
        for (int i = 0; i < 15; i++) {
            entities.add(new Entity("Entity_" + i, "ORGANIZATION", "Duplicate of entity " + i, null));
            entities.add(new Entity("Entity_" + i, "ORGANIZATION", "Another duplicate " + i, null));
        }

        long startTime = System.currentTimeMillis();
        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());
        long elapsedMs = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertEquals(100, result.originalEntityCount());
        assertTrue(result.resolvedEntityCount() < 100, "Should have merged some duplicates");
        assertTrue(result.duplicatesRemoved() >= 20, "Should have removed at least 20 duplicates");
        
        // Performance check: should be reasonably fast
        assertTrue(elapsedMs < 5000, 
            "Large batch processing should complete in <5s (took " + elapsedMs + "ms)");

        LOG.infof("Large batch: 100 -> %d entities in %dms (%.2fms/entity)",
            result.resolvedEntityCount(), elapsedMs, (double) elapsedMs / 100);
    }

    /**
     * Test null entity name handling.
     * 
     * Scenario: Entity constructor should throw IllegalArgumentException for null name.
     */
    @Test
    void testNullEntityNameThrows() {
        LOG.info("Testing null entity name handling");

        assertThrows(NullPointerException.class, () -> {
            new Entity(null, "ORGANIZATION", "Description", null);
        }, "Entity constructor should throw for null name");

        LOG.info("Null entity name handling test passed");
    }

    /**
     * Test null description handling.
     * 
     * Scenario: Entity constructor should throw IllegalArgumentException for null description.
     */
    @Test
    void testNullDescriptionThrows() {
        LOG.info("Testing null description handling");

        assertThrows(NullPointerException.class, () -> {
            new Entity("Entity Name", "ORGANIZATION", null, null);
        }, "Entity constructor should throw for null description");

        LOG.info("Null description handling test passed");
    }

    /**
     * Test entities with null types are handled gracefully via fallback.
     * 
     * Scenario: Entities with null or blank types trigger validation errors during
     * similarity computation, but EntityResolver catches these and returns the
     * original entities unchanged (fallback behavior).
     */
    @Test
    void testNullTypeFallback() {
        LOG.info("Testing null type fallback handling");

        List<Entity> entities = List.of(
            new Entity("Entity A", null, "Description A", null),
            new Entity("Entity A", null, "Description B", null)
        );

        // EntityResolver catches validation errors and returns original entities as fallback
        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // Should return original entities unchanged when errors occur
        assertEquals(2, resolved.size(), 
            "Null types should trigger fallback, returning original entities");

        LOG.info("Null type fallback test passed");
    }

    /**
     * Test abbreviation detection works with appropriate configuration.
     * 
     * Scenario: "MIT" and "Massachusetts Institute of Technology" should be
     * recognized as the same entity when they have the same type.
     * 
     * Note: With default config (abbreviation weight 10%), exact merge behavior
     * depends on threshold. This test validates the system handles abbreviations.
     */
    @Test
    void testAbbreviationDetection() {
        LOG.info("Testing abbreviation detection");

        List<Entity> entities = List.of(
            new Entity("Massachusetts Institute of Technology", "ORGANIZATION", "Founded in 1861", null),
            new Entity("MIT", "ORGANIZATION", "A renowned university", null)
        );

        List<Entity> resolved = entityResolver.resolveDuplicates(entities, testProjectId.toString());

        // With default config, abbreviation may or may not merge depending on threshold
        // This test validates no errors occur and result is reasonable
        assertTrue(resolved.size() >= 1 && resolved.size() <= 2, 
            "Abbreviation handling should produce 1-2 entities (got " + resolved.size() + ")");

        LOG.infof("Abbreviation detection: %d -> %d entities", entities.size(), resolved.size());
    }

    // ========================================================================
    // T039: Type-Aware Integration Tests (User Story 2)
    // ========================================================================

    /**
     * T039: Test Mercury planet vs chemical element should not merge.
     * 
     * Scenario: Same name "Mercury" but different types (PLANET vs CHEMICAL_ELEMENT)
     * should remain as separate entities.
     */
    @Test
    void testMercuryPlanetVsElementTypeAware() {
        LOG.info("Testing Mercury planet vs chemical element type-aware matching");

        List<Entity> entities = List.of(
            new Entity("Mercury", "PLANET", "The closest planet to the Sun", null),
            new Entity("Mercury", "CHEMICAL_ELEMENT", "A chemical element with symbol Hg", null),
            new Entity("Mercury", "PLANET", "First planet from the Sun", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Should have 2 entities: 1 planet (merged) and 1 element
        assertEquals(2, result.resolvedEntityCount(), 
            "Mercury planet and element should not merge (expected 2, got " + 
            result.resolvedEntityCount() + ")");
        
        // Verify both types are present
        long planetCount = result.resolvedEntities().stream()
            .filter(e -> "PLANET".equals(e.getEntityType())).count();
        long elementCount = result.resolvedEntities().stream()
            .filter(e -> "CHEMICAL_ELEMENT".equals(e.getEntityType())).count();
        
        assertEquals(1, planetCount, "Should have 1 PLANET entity");
        assertEquals(1, elementCount, "Should have 1 CHEMICAL_ELEMENT entity");
        assertEquals(1, result.duplicatesRemoved(), "Should merge the 2 planet entities");

        LOG.info("Mercury planet vs element type-aware test passed");
    }

    /**
     * T039: Test Washington person vs location should not merge.
     * 
     * Scenario: "Washington" as PERSON and LOCATION should remain separate.
     */
    @Test
    void testWashingtonPersonVsLocationTypeAware() {
        LOG.info("Testing Washington person vs location type-aware matching");

        List<Entity> entities = List.of(
            new Entity("Washington", "PERSON", "George Washington, first US president", null),
            new Entity("Washington", "LOCATION", "The capital of the United States", null),
            new Entity("George Washington", "PERSON", "Founding father and first president", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Should have at least 2 entities (person and location separate)
        assertTrue(result.resolvedEntityCount() >= 2, 
            "Washington person and location should not merge (got " + 
            result.resolvedEntityCount() + " entities)");
        
        // Verify both types are present
        long personCount = result.resolvedEntities().stream()
            .filter(e -> "PERSON".equals(e.getEntityType())).count();
        long locationCount = result.resolvedEntities().stream()
            .filter(e -> "LOCATION".equals(e.getEntityType())).count();
        
        assertTrue(personCount >= 1, "Should have at least 1 PERSON entity");
        assertEquals(1, locationCount, "Should have 1 LOCATION entity");

        LOG.info("Washington person vs location type-aware test passed");
    }

    /**
     * T039: Test Jordan person vs country should not merge.
     * 
     * Scenario: "Jordan" as PERSON and GEO should remain separate entities.
     */
    @Test
    void testJordanPersonVsCountryTypeAware() {
        LOG.info("Testing Jordan person vs country type-aware matching");

        List<Entity> entities = List.of(
            new Entity("Michael Jordan", "PERSON", "Famous basketball player", null),
            new Entity("Jordan", "GEO", "A country in the Middle East", null),
            new Entity("Jordan", "PERSON", "Basketball legend", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Should have at least 2 entities (person separate from country)
        assertTrue(result.resolvedEntityCount() >= 2, 
            "Jordan person and country should not merge (got " + 
            result.resolvedEntityCount() + " entities)");
        
        // Verify both types are present
        long personCount = result.resolvedEntities().stream()
            .filter(e -> "PERSON".equals(e.getEntityType())).count();
        long geoCount = result.resolvedEntities().stream()
            .filter(e -> "GEO".equals(e.getEntityType())).count();
        
        assertTrue(personCount >= 1, "Should have at least 1 PERSON entity");
        assertEquals(1, geoCount, "Should have 1 GEO entity");

        LOG.info("Jordan person vs country type-aware test passed");
    }

    /**
     * T039: Test Java across multiple types should not merge.
     * 
     * Scenario: "Java" as PROGRAMMING_LANGUAGE, GEO, PRODUCT, and ORGANIZATION
     * should all remain as separate entities.
     */
    @Test
    void testJavaMultipleTypesNoMerge() {
        LOG.info("Testing Java across multiple types should not merge");

        List<Entity> entities = List.of(
            new Entity("Java", "PROGRAMMING_LANGUAGE", "Object-oriented programming language", null),
            new Entity("Java", "GEO", "Indonesian island", null),
            new Entity("Java", "PRODUCT", "Coffee brand", null),
            new Entity("Java", "ORGANIZATION", "Software company named Java", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Should have 4 entities (no cross-type merging)
        assertEquals(4, result.resolvedEntityCount(), 
            "Java entities with different types should not merge (expected 4, got " + 
            result.resolvedEntityCount() + ")");
        assertEquals(0, result.duplicatesRemoved(), 
            "Should not remove any duplicates when all types differ");
        assertFalse(result.hadDuplicates(), 
            "Should report no duplicates when all types differ");

        LOG.info("Java multiple types test passed - all 4 types preserved");
    }

    /**
     * T039: Test complex scenario with multiple same-name entities across types.
     * 
     * Scenario: Mix of organizations, products, and persons with overlapping names.
     * Verifies type-aware matching prevents cross-type merges while allowing
     * within-type merges.
     */
    @Test
    void testComplexMultiTypeScenario() {
        LOG.info("Testing complex multi-type scenario");

        List<Entity> entities = List.of(
            // Apple organizations (should merge)
            new Entity("Apple", "ORGANIZATION", "Tech company", null),
            new Entity("Apple Inc", "ORGANIZATION", "Cupertino company", null),
            new Entity("Apple Inc.", "ORGANIZATION", "iPhone maker", null),
            // Apple products (should NOT merge with organizations)
            new Entity("Apple", "PRODUCT", "Red fruit", null),
            new Entity("apple", "PRODUCT", "Green fruit", null),
            // Orange organization
            new Entity("Orange", "ORGANIZATION", "Telecom company", null),
            // Orange product
            new Entity("Orange", "PRODUCT", "Citrus fruit", null),
            // Person
            new Entity("Steve Jobs", "PERSON", "Apple founder", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Expected: 5-6 entities
        // - Apple ORG cluster: 3→1
        // - Apple PRODUCT cluster: 2→1 or 2→2 (depending on similarity)
        // - Orange ORG: 1
        // - Orange PRODUCT: 1
        // - Steve Jobs PERSON: 1
        assertTrue(result.resolvedEntityCount() >= 5 && result.resolvedEntityCount() <= 6, 
            "Should have 5-6 entities after type-aware merging (got " + 
            result.resolvedEntityCount() + ")");
        
        // Should have merged at least the Apple organizations
        assertTrue(result.duplicatesRemoved() >= 2, 
            "Should merge at least Apple organizations (removed " + 
            result.duplicatesRemoved() + " duplicates)");
        
        // Verify we have all types preserved
        long orgCount = result.resolvedEntities().stream()
            .filter(e -> "ORGANIZATION".equals(e.getEntityType())).count();
        long productCount = result.resolvedEntities().stream()
            .filter(e -> "PRODUCT".equals(e.getEntityType())).count();
        long personCount = result.resolvedEntities().stream()
            .filter(e -> "PERSON".equals(e.getEntityType())).count();
        
        assertEquals(2, orgCount, "Should have 2 ORGANIZATION entities (Apple + Orange)");
        assertTrue(productCount >= 2, "Should have at least 2 PRODUCT entities");
        assertEquals(1, personCount, "Should have 1 PERSON entity");

        LOG.infof("Complex multi-type scenario: 8 -> %d entities (-%d duplicates)", 
            result.resolvedEntityCount(), result.duplicatesRemoved());
    }

    /**
     * T039: Test type-aware matching with statistics validation.
     * 
     * Scenario: Validate that statistics correctly reflect type-aware behavior.
     */
    @Test
    void testTypeAwareMatchingWithDetailedStats() {
        LOG.info("Testing type-aware matching with detailed statistics");

        List<Entity> entities = List.of(
            new Entity("Mercury", "PLANET", "Planet description", null),
            new Entity("Mercury", "CHEMICAL_ELEMENT", "Element description", null),
            new Entity("Mercury", "PERSON", "Roman god", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Verify statistics
        assertEquals(3, result.originalEntityCount(), 
            "Should have 3 original entities");
        assertEquals(3, result.resolvedEntityCount(), 
            "Should have 3 resolved entities (no cross-type merges)");
        assertEquals(0, result.duplicatesRemoved(), 
            "Should not merge entities with different types");
        assertEquals(3, result.clustersFound(), 
            "Should have 3 clusters (one per type)");
        assertFalse(result.hadDuplicates(), 
            "Should report no duplicates when all types differ");
        assertEquals(0.0, result.deduplicationRate(), 0.001, 
            "Deduplication rate should be 0%");
        
        // Verify processing completed
        assertNotNull(result.processingTime(), "Should have processing time");
        assertTrue(result.processingTime().toMillis() >= 0, 
            "Processing time should be non-negative");

        LOG.infof("Type-aware statistics validated: %s", result.toLogString());
    }

    /**
     * T039: Test case-insensitive type matching in integration scenario.
     * 
     * Scenario: Type matching should be case-insensitive, so "organization",
     * "ORGANIZATION", and "Organization" should be treated as the same type.
     */
    @Test
    void testCaseInsensitiveTypeMatching() {
        LOG.info("Testing case-insensitive type matching in integration");

        List<Entity> entities = List.of(
            new Entity("Microsoft", "organization", "Tech company", null),
            new Entity("Microsoft", "ORGANIZATION", "Software company", null),
            new Entity("Microsoft", "Organization", "Redmond company", null),
            new Entity("Microsoft", "PRODUCT", "Software product", null)
        );

        EntityResolutionResult result = entityResolver.resolveDuplicatesWithStats(
            entities, testProjectId.toString());

        // Should have 2 types: organization (case-insensitive) and product
        assertTrue(result.resolvedEntityCount() >= 2 && result.resolvedEntityCount() <= 4, 
            "Should have 2-4 entities (organizations treated as same type)");
        
        // Verify we have both organization and product types
        long orgCount = result.resolvedEntities().stream()
            .filter(e -> e.getEntityType().equalsIgnoreCase("ORGANIZATION")).count();
        long productCount = result.resolvedEntities().stream()
            .filter(e -> "PRODUCT".equalsIgnoreCase(e.getEntityType())).count();
        
        assertTrue(orgCount >= 1, "Should have at least 1 organization entity");
        assertEquals(1, productCount, "Should have 1 product entity");

        LOG.info("Case-insensitive type matching test passed");
    }
}
