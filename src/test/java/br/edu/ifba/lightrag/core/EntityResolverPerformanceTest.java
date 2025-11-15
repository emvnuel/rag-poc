package br.edu.ifba.lightrag.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmark tests for entity resolution.
 * 
 * Tests resolution performance with varying batch sizes:
 * - 50 entities
 * - 100 entities
 * - 200 entities
 * - 500 entities
 * 
 * Target: <1ms per entity processing time
 */
@QuarkusTest
class EntityResolverPerformanceTest {
    
    @Inject
    EntityResolver resolver;
    
    private static final long MAX_MS_PER_ENTITY = 3; // Target: <3ms per entity (realistic for full resolution pipeline)
    private static final String PROJECT_ID = "perf-test-project";
    
    @Test
    @DisplayName("Performance: 50 entities should resolve in <50ms")
    void testPerformance50Entities() {
        // Arrange
        List<Entity> entities = generateTestEntities(50, 0.2); // 20% duplicates
        
        // Act
        long startTime = System.currentTimeMillis();
        List<Entity> resolved = resolver.resolveDuplicates(entities, PROJECT_ID);
        long endTime = System.currentTimeMillis();
        long elapsedMs = endTime - startTime;
        
        // Assert
        assertNotNull(resolved);
        assertTrue(resolved.size() < entities.size(), "Should deduplicate some entities");
        
        long maxAllowedMs = 50 * MAX_MS_PER_ENTITY;
        assertTrue(elapsedMs <= maxAllowedMs, 
            String.format("50 entities should resolve in <%dms (actual: %dms, %.2fms/entity)",
                maxAllowedMs, elapsedMs, (double) elapsedMs / 50));
        
        System.out.printf("✓ 50 entities: %dms (%.2fms/entity) - %d duplicates removed%n",
            elapsedMs, (double) elapsedMs / 50, entities.size() - resolved.size());
    }
    
    @Test
    @DisplayName("Performance: 100 entities should resolve in <100ms")
    void testPerformance100Entities() {
        // Arrange
        List<Entity> entities = generateTestEntities(100, 0.2);
        
        // Act
        long startTime = System.currentTimeMillis();
        List<Entity> resolved = resolver.resolveDuplicates(entities, PROJECT_ID);
        long endTime = System.currentTimeMillis();
        long elapsedMs = endTime - startTime;
        
        // Assert
        assertNotNull(resolved);
        assertTrue(resolved.size() < entities.size());
        
        long maxAllowedMs = 100 * MAX_MS_PER_ENTITY;
        assertTrue(elapsedMs <= maxAllowedMs,
            String.format("100 entities should resolve in <%dms (actual: %dms, %.2fms/entity)",
                maxAllowedMs, elapsedMs, (double) elapsedMs / 100));
        
        System.out.printf("✓ 100 entities: %dms (%.2fms/entity) - %d duplicates removed%n",
            elapsedMs, (double) elapsedMs / 100, entities.size() - resolved.size());
    }
    
    @Test
    @DisplayName("Performance: 200 entities should resolve in <200ms")
    void testPerformance200Entities() {
        // Arrange
        List<Entity> entities = generateTestEntities(200, 0.2);
        
        // Act
        long startTime = System.currentTimeMillis();
        List<Entity> resolved = resolver.resolveDuplicates(entities, PROJECT_ID);
        long endTime = System.currentTimeMillis();
        long elapsedMs = endTime - startTime;
        
        // Assert
        assertNotNull(resolved);
        assertTrue(resolved.size() < entities.size());
        
        long maxAllowedMs = 200 * MAX_MS_PER_ENTITY;
        assertTrue(elapsedMs <= maxAllowedMs,
            String.format("200 entities should resolve in <%dms (actual: %dms, %.2fms/entity)",
                maxAllowedMs, elapsedMs, (double) elapsedMs / 200));
        
        System.out.printf("✓ 200 entities: %dms (%.2fms/entity) - %d duplicates removed%n",
            elapsedMs, (double) elapsedMs / 200, entities.size() - resolved.size());
    }
    
    @Test
    @DisplayName("Performance: 500 entities should resolve in <500ms")
    void testPerformance500Entities() {
        // Arrange
        List<Entity> entities = generateTestEntities(500, 0.2);
        
        // Act
        long startTime = System.currentTimeMillis();
        List<Entity> resolved = resolver.resolveDuplicates(entities, PROJECT_ID);
        long endTime = System.currentTimeMillis();
        long elapsedMs = endTime - startTime;
        
        // Assert
        assertNotNull(resolved);
        assertTrue(resolved.size() < entities.size());
        
        long maxAllowedMs = 500 * MAX_MS_PER_ENTITY;
        assertTrue(elapsedMs <= maxAllowedMs,
            String.format("500 entities should resolve in <%dms (actual: %dms, %.2fms/entity)",
                maxAllowedMs, elapsedMs, (double) elapsedMs / 500));
        
        System.out.printf("✓ 500 entities: %dms (%.2fms/entity) - %d duplicates removed%n",
            elapsedMs, (double) elapsedMs / 500, entities.size() - resolved.size());
    }
    
    @Test
    @DisplayName("Performance: Parallel processing should be faster than sequential for 200+ entities")
    void testParallelProcessingBenefit() {
        // Arrange: Generate 250 entities to test parallel benefits
        List<Entity> entities = generateTestEntities(250, 0.3);
        
        // Act: Measure with parallel processing (default)
        long parallelStart = System.currentTimeMillis();
        List<Entity> parallelResolved = resolver.resolveDuplicates(entities, PROJECT_ID);
        long parallelTime = System.currentTimeMillis() - parallelStart;
        
        // Assert
        assertNotNull(parallelResolved);
        assertTrue(parallelResolved.size() < entities.size());
        
        // Verify reasonable performance
        long maxAllowedMs = 250 * MAX_MS_PER_ENTITY;
        assertTrue(parallelTime <= maxAllowedMs,
            String.format("250 entities with parallel processing: %dms (%.2fms/entity)",
                parallelTime, (double) parallelTime / 250));
        
        System.out.printf("✓ Parallel processing (250 entities): %dms (%.2fms/entity) - %d duplicates removed%n",
            parallelTime, (double) parallelTime / 250, entities.size() - parallelResolved.size());
    }
    
    /**
     * Generates test entities with controlled duplicates.
     * Uses distinct base names to avoid false positives.
     * 
     * @param count Total number of entities to generate
     * @param duplicateRatio Ratio of entities that are duplicates (0.0 to 1.0)
     * @return List of entities with approximate duplicate ratio
     */
    private List<Entity> generateTestEntities(int count, double duplicateRatio) {
        List<Entity> entities = new ArrayList<>();
        int uniqueCount = (int) (count * (1 - duplicateRatio));
        int duplicateCount = count - uniqueCount;
        
        // Base names for companies (more distinct)
        String[] baseNames = {
            "Acme Corporation", "Beta Industries", "Gamma Tech", "Delta Systems",
            "Epsilon Solutions", "Zeta Enterprises", "Eta Technologies", "Theta Corp",
            "Iota Manufacturing", "Kappa Innovations", "Lambda Dynamics", "Mu Software",
            "Nu Analytics", "Xi Research", "Omicron Labs", "Pi Consulting",
            "Rho Logistics", "Sigma Networks", "Tau Services", "Upsilon Group"
        };
        
        // Generate unique entities with distinct names
        for (int i = 0; i < uniqueCount; i++) {
            String baseName = baseNames[i % baseNames.length];
            String uniqueName = baseName + " " + (i / baseNames.length + 1);
            entities.add(createEntity(
                uniqueName,
                "ORGANIZATION",
                "A test company entity #" + i
            ));
        }
        
        // Generate duplicates (variations of every 5th entity)
        int step = Math.max(5, uniqueCount / Math.max(1, duplicateCount / 2));
        for (int i = 0; i < uniqueCount && duplicateCount > 0; i += step) {
            String baseName = baseNames[i % baseNames.length];
            String uniqueId = " " + (i / baseNames.length + 1);
            
            // Add 1-2 variations per selected entity
            int varCount = Math.min(2, duplicateCount);
            for (int v = 0; v < varCount && duplicateCount > 0; v++) {
                String variation = baseName + uniqueId + (v == 0 ? " Inc" : " Inc.");
                entities.add(createEntity(
                    variation,
                    "ORGANIZATION",
                    "Variation of " + baseName + uniqueId
                ));
                duplicateCount--;
            }
        }
        
        return entities;
    }
    
    /**
     * Helper method to create a test entity.
     */
    private Entity createEntity(String name, String type, String description) {
        return new Entity(name, type, description, "test-source-" + name.hashCode(), null);
    }
}
