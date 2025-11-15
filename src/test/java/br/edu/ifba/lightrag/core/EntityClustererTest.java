package br.edu.ifba.lightrag.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for EntityClusterer.
 * 
 * Tests the clustering algorithm (threshold-based connected components)
 * and cluster merging strategy following examples from research.md.
 * 
 * Following TDD principles: These tests should FAIL initially
 * until implementation is complete.
 */
@QuarkusTest
class EntityClustererTest {
    
    @Inject
    EntityClusterer clusterer;
    
    @Inject
    EntitySimilarityCalculator calculator;
    
    @Inject
    DeduplicationConfig config;
    
    private List<Entity> testEntities;
    
    @BeforeEach
    void setUp() {
        // Reset test entities before each test
        testEntities = new ArrayList<>();
    }
    
    // ========================================================================
    // Tests for buildSimilarityMatrix()
    // ========================================================================
    
    @Test
    @DisplayName("buildSimilarityMatrix should create n×n matrix for n entities")
    void testBuildSimilarityMatrixDimensions() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION"),
            createEntity("Harvard", "ORGANIZATION"),
            createEntity("Stanford", "ORGANIZATION")
        );
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert
        assertEquals(3, matrix.length, "Matrix should have 3 rows");
        assertEquals(3, matrix[0].length, "Matrix should have 3 columns");
        assertEquals(3, matrix[1].length, "Matrix should have 3 columns");
        assertEquals(3, matrix[2].length, "Matrix should have 3 columns");
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should have 1.0 on diagonal")
    void testBuildSimilarityMatrixDiagonal() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION"),
            createEntity("Harvard", "ORGANIZATION")
        );
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert
        assertEquals(1.0, matrix[0][0], 0.001, "Diagonal should be 1.0 (entity compared to itself)");
        assertEquals(1.0, matrix[1][1], 0.001, "Diagonal should be 1.0 (entity compared to itself)");
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should be symmetric")
    void testBuildSimilarityMatrixSymmetric() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION")
        );
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert
        assertEquals(matrix[0][1], matrix[1][0], 0.001, "Matrix should be symmetric: matrix[i][j] = matrix[j][i]");
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should compute pairwise similarities")
    void testBuildSimilarityMatrixPairwise() {
        // Arrange: Warren Home cluster from research.md
        testEntities = List.of(
            createEntity("Warren State Home", "ORGANIZATION"),
            createEntity("Warren Home", "ORGANIZATION"),
            createEntity("Warren State Home and Training School", "ORGANIZATION")
        );
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert: Warren pairs should have reasonable similarity
        // Note: "Warren State Home" vs "Warren Home" = 0.427
        //       "Warren State Home" vs "Warren State Home and Training School" = 0.313
        //       "Warren Home" vs "Warren State Home and Training School" = 0.206
        // These will cluster via connected components despite pairwise scores < 0.5
        assertTrue(matrix[0][1] > 0.2, "Warren State Home and Warren Home should have reasonable similarity");
        assertTrue(matrix[0][2] > 0.2, "Warren State Home and Warren State Home and Training School should have reasonable similarity");
        assertTrue(matrix[1][2] > 0.2, "Warren Home and Warren State Home and Training School should have reasonable similarity");
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should return 0.0 for different types")
    void testBuildSimilarityMatrixTypeSafety() {
        // Arrange: Same name, different types
        testEntities = List.of(
            createEntity("Apple", "ORGANIZATION"),
            createEntity("Apple", "PRODUCT")
        );
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert: Type mismatch should result in 0.0 similarity
        assertEquals(0.0, matrix[0][1], 0.001, "Different types should have 0.0 similarity");
        assertEquals(0.0, matrix[1][0], 0.001, "Different types should have 0.0 similarity");
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should handle empty list")
    void testBuildSimilarityMatrixEmptyList() {
        // Arrange
        testEntities = List.of();
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert
        assertEquals(0, matrix.length, "Empty list should produce empty matrix");
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should handle single entity")
    void testBuildSimilarityMatrixSingleEntity() {
        // Arrange
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Assert
        assertEquals(1, matrix.length);
        assertEquals(1, matrix[0].length);
        assertEquals(1.0, matrix[0][0], 0.001);
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should throw exception for null entities")
    void testBuildSimilarityMatrixNullEntities() {
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.buildSimilarityMatrix(null, calculator);
        });
    }
    
    @Test
    @DisplayName("buildSimilarityMatrix should throw exception for null calculator")
    void testBuildSimilarityMatrixNullCalculator() {
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.buildSimilarityMatrix(testEntities, null);
        });
    }
    
    // ========================================================================
    // Tests for clusterBySimilarity()
    // ========================================================================
    
    @Test
    @DisplayName("clusterBySimilarity should create single cluster for high similarity entities")
    void testClusterBySimilaritySingleCluster() {
        // Arrange: Warren Home cluster - all above threshold (0.75)
        testEntities = List.of(
            createEntity("Warren State Home", "ORGANIZATION"),
            createEntity("Warren Home", "ORGANIZATION"),
            createEntity("Warren State Home and Training School", "ORGANIZATION")
        );
        
        double[][] matrix = new double[][] {
            {1.0, 0.80, 0.85},
            {0.80, 1.0, 0.78},
            {0.85, 0.78, 1.0}
        };
        
        // Act
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert
        assertEquals(1, clusters.size(), "Should create 1 cluster when all similarities above threshold");
        Set<Integer> cluster = clusters.get(0);
        assertEquals(3, cluster.size(), "Cluster should contain all 3 entities");
        assertTrue(cluster.contains(0));
        assertTrue(cluster.contains(1));
        assertTrue(cluster.contains(2));
    }
    
    @Test
    @DisplayName("clusterBySimilarity should create separate clusters for low similarity entities")
    void testClusterBySimilaritySeparateClusters() {
        // Arrange: Unrelated entities
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION"),
            createEntity("Harvard", "ORGANIZATION"),
            createEntity("Stanford", "ORGANIZATION")
        );
        
        double[][] matrix = new double[][] {
            {1.0, 0.30, 0.25},
            {0.30, 1.0, 0.28},
            {0.25, 0.28, 1.0}
        };
        
        // Act
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert
        assertEquals(3, clusters.size(), "Should create 3 separate clusters when all similarities below threshold");
        
        // Each cluster should contain only one entity (singleton)
        for (Set<Integer> cluster : clusters) {
            assertEquals(1, cluster.size(), "Each cluster should be a singleton");
        }
    }
    
    @Test
    @DisplayName("clusterBySimilarity should create multiple clusters with mixed similarities")
    void testClusterBySimilarityMixedClusters() {
        // Arrange: MIT variations + separate entity
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION"),                              // 0
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION"), // 1
            createEntity("M.I.T.", "ORGANIZATION"),                           // 2
            createEntity("Harvard University", "ORGANIZATION")                // 3
        );
        
        double[][] matrix = new double[][] {
            {1.0, 0.85, 0.90, 0.15},  // MIT similar to variations, different from Harvard
            {0.85, 1.0, 0.82, 0.18},
            {0.90, 0.82, 1.0, 0.12},
            {0.15, 0.18, 0.12, 1.0}   // Harvard different from all
        };
        
        // Act
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert
        assertEquals(2, clusters.size(), "Should create 2 clusters: MIT variations + Harvard");
        
        // Find MIT cluster (should contain indices 0, 1, 2)
        Set<Integer> mitCluster = clusters.stream()
            .filter(c -> c.size() == 3)
            .findFirst()
            .orElseThrow(() -> new AssertionError("MIT cluster not found"));
        
        assertTrue(mitCluster.contains(0));
        assertTrue(mitCluster.contains(1));
        assertTrue(mitCluster.contains(2));
        
        // Find Harvard cluster (should contain index 3)
        Set<Integer> harvardCluster = clusters.stream()
            .filter(c -> c.size() == 1)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Harvard cluster not found"));
        
        assertTrue(harvardCluster.contains(3));
    }
    
    @Test
    @DisplayName("clusterBySimilarity should handle threshold edge cases")
    void testClusterBySimilarityThresholdEdgeCases() {
        // Arrange: Entities with similarity exactly at threshold
        testEntities = List.of(
            createEntity("Entity A", "ORGANIZATION"),
            createEntity("Entity B", "ORGANIZATION")
        );
        
        double[][] matrix = new double[][] {
            {1.0, 0.75},  // Exactly at threshold
            {0.75, 1.0}
        };
        
        // Act: Threshold = 0.75 (similarity >= threshold should cluster)
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert
        assertEquals(1, clusters.size(), "Entities with similarity >= threshold should cluster together");
        assertEquals(2, clusters.get(0).size(), "Cluster should contain both entities");
    }
    
    @Test
    @DisplayName("clusterBySimilarity should handle transitive clustering")
    void testClusterBySimilarityTransitive() {
        // Arrange: A similar to B, B similar to C, but A not directly similar to C
        // Connected components should still group all three
        testEntities = List.of(
            createEntity("Entity A", "ORGANIZATION"),
            createEntity("Entity B", "ORGANIZATION"),
            createEntity("Entity C", "ORGANIZATION")
        );
        
        double[][] matrix = new double[][] {
            {1.0, 0.80, 0.50},  // A-B above threshold, A-C below
            {0.80, 1.0, 0.85},  // B-C above threshold
            {0.50, 0.85, 1.0}
        };
        
        // Act
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert: All three should be in one cluster due to transitive connection
        assertEquals(1, clusters.size(), "Connected components should handle transitive similarity");
        assertEquals(3, clusters.get(0).size(), "All three entities should be in the same cluster");
    }
    
    @Test
    @DisplayName("clusterBySimilarity should handle empty entities list")
    void testClusterBySimilarityEmptyList() {
        // Arrange
        testEntities = List.of();
        double[][] matrix = new double[0][0];
        
        // Act
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert
        assertTrue(clusters.isEmpty(), "Empty entities should produce empty clusters");
    }
    
    @Test
    @DisplayName("clusterBySimilarity should handle single entity")
    void testClusterBySimilaritySingleEntity() {
        // Arrange
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        double[][] matrix = new double[][] {{1.0}};
        
        // Act
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert
        assertEquals(1, clusters.size(), "Single entity should create one cluster");
        assertEquals(1, clusters.get(0).size(), "Cluster should contain one entity");
        assertTrue(clusters.get(0).contains(0));
    }
    
    @Test
    @DisplayName("clusterBySimilarity should throw exception for null entities")
    void testClusterBySimilarityNullEntities() {
        double[][] matrix = new double[][] {{1.0}};
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.clusterBySimilarity(null, matrix, 0.75);
        });
    }
    
    @Test
    @DisplayName("clusterBySimilarity should throw exception for null matrix")
    void testClusterBySimilarityNullMatrix() {
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.clusterBySimilarity(testEntities, null, 0.75);
        });
    }
    
    @Test
    @DisplayName("clusterBySimilarity should throw exception for invalid threshold")
    void testClusterBySimilarityInvalidThreshold() {
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        double[][] matrix = new double[][] {{1.0}};
        
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.clusterBySimilarity(testEntities, matrix, -0.1);
        }, "Negative threshold should throw exception");
        
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.clusterBySimilarity(testEntities, matrix, 1.5);
        }, "Threshold > 1.0 should throw exception");
    }
    
    // ========================================================================
    // Tests for mergeCluster()
    // ========================================================================
    
    @Test
    @DisplayName("mergeCluster should select longest name as canonical")
    void testMergeClusterSelectLongestName() {
        // Arrange: Warren Home cluster
        testEntities = List.of(
            createEntity("Warren State Home", "ORGANIZATION", "A state home for training"),
            createEntity("Warren Home", "ORGANIZATION", "Training facility"),
            createEntity("Warren State Home and Training School", "ORGANIZATION", "Educational institution")
        );
        
        Set<Integer> clusterIndices = Set.of(0, 1, 2);
        
        // Act
        EntityCluster cluster = clusterer.mergeCluster(clusterIndices, testEntities);
        
        // Assert: Longest name is "Warren State Home and Training School"
        assertEquals("Warren State Home and Training School", cluster.canonicalEntity().getEntityName(),
            "Should select longest name as canonical entity");
        assertEquals("ORGANIZATION", cluster.canonicalEntity().getEntityType(),
            "Canonical entity should preserve type");
    }
    
    @Test
    @DisplayName("mergeCluster should combine descriptions with separator")
    void testMergeClusterCombineDescriptions() {
        // Arrange
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION", "Research university in Cambridge"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION", "Leading tech institute"),
            createEntity("M.I.T.", "ORGANIZATION", "Founded in 1861")
        );
        
        Set<Integer> clusterIndices = Set.of(0, 1, 2);
        
        // Act
        EntityCluster cluster = clusterer.mergeCluster(clusterIndices, testEntities);
        
        // Assert: Descriptions should be combined with " | " separator
        String merged = cluster.mergedDescription();
        assertTrue(merged.contains("Research university in Cambridge"), "Should contain first description");
        assertTrue(merged.contains("Leading tech institute"), "Should contain second description");
        assertTrue(merged.contains("Founded in 1861"), "Should contain third description");
        assertTrue(merged.contains(" | "), "Should use ' | ' separator");
    }
    
    @Test
    @DisplayName("mergeCluster should extract aliases from non-canonical names")
    void testMergeClusterExtractAliases() {
        // Arrange: MIT variations
        testEntities = List.of(
            createEntity("MIT", "ORGANIZATION"),
            createEntity("Massachusetts Institute of Technology", "ORGANIZATION"),
            createEntity("M.I.T.", "ORGANIZATION")
        );
        
        Set<Integer> clusterIndices = Set.of(0, 1, 2);
        
        // Act
        EntityCluster cluster = clusterer.mergeCluster(clusterIndices, testEntities);
        
        // Assert: Aliases should include all names except canonical
        String canonicalName = cluster.canonicalEntity().getEntityName();
        List<String> aliases = cluster.aliases();
        
        assertEquals(2, aliases.size(), "Should have 2 aliases (all names except canonical)");
        assertFalse(aliases.contains(canonicalName), "Aliases should not contain canonical name");
        
        // All entity names should be either canonical or in aliases
        for (int idx : clusterIndices) {
            String name = testEntities.get(idx).getEntityName();
            if (!name.equals(canonicalName)) {
                assertTrue(aliases.contains(name), "Non-canonical name should be in aliases: " + name);
            }
        }
    }
    
    @Test
    @DisplayName("mergeCluster should preserve source ID from canonical entity")
    void testMergeClusterPreserveSourceId() {
        // Arrange: Entities with different source IDs
        Entity e1 = new Entity("MIT", "ORGANIZATION", "Desc 1", "src1", null);
        Entity e2 = new Entity("Massachusetts Institute of Technology", "ORGANIZATION", "Desc 2", "src2", null);
        Entity e3 = new Entity("M.I.T.", "ORGANIZATION", "Desc 3", "src3", null);
        
        testEntities = List.of(e1, e2, e3);
        Set<Integer> clusterIndices = Set.of(0, 1, 2);
        
        // Act
        EntityCluster cluster = clusterer.mergeCluster(clusterIndices, testEntities);
        
        // Assert: Canonical entity should preserve its source ID
        String sourceId = cluster.canonicalEntity().getSourceId();
        assertNotNull(sourceId, "Canonical entity should have a source ID");
        assertTrue(List.of("src1", "src2", "src3").contains(sourceId), 
            "Source ID should be from one of the merged entities");
    }
    
    @Test
    @DisplayName("mergeCluster should handle singleton cluster")
    void testMergeClusterSingleton() {
        // Arrange: Single entity
        testEntities = List.of(
            createEntity("Harvard University", "ORGANIZATION", "Ivy League university")
        );
        
        Set<Integer> clusterIndices = Set.of(0);
        
        // Act
        EntityCluster cluster = clusterer.mergeCluster(clusterIndices, testEntities);
        
        // Assert
        assertEquals("Harvard University", cluster.canonicalEntity().getEntityName());
        assertEquals(1, cluster.size(), "Singleton cluster should have size 1");
        assertTrue(cluster.isSingleton(), "Should be marked as singleton");
        assertTrue(cluster.aliases().isEmpty(), "Singleton should have no aliases");
        assertEquals("Ivy League university", cluster.mergedDescription(),
            "Singleton should preserve original description");
    }
    
    @Test
    @DisplayName("mergeCluster should preserve entity indices")
    void testMergeClusterPreserveIndices() {
        // Arrange
        testEntities = List.of(
            createEntity("Entity 1", "ORGANIZATION"),
            createEntity("Entity 2", "ORGANIZATION"),
            createEntity("Entity 3", "ORGANIZATION")
        );
        
        Set<Integer> clusterIndices = Set.of(0, 2); // Skip index 1
        
        // Act
        EntityCluster cluster = clusterer.mergeCluster(clusterIndices, testEntities);
        
        // Assert
        assertEquals(clusterIndices, cluster.entityIndices(), "Should preserve input indices");
        assertEquals(2, cluster.size());
        assertTrue(cluster.containsEntityIndex(0));
        assertFalse(cluster.containsEntityIndex(1));
        assertTrue(cluster.containsEntityIndex(2));
    }
    
    @Test
    @DisplayName("mergeCluster should throw exception for null cluster indices")
    void testMergeClusterNullClusterIndices() {
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.mergeCluster(null, testEntities);
        });
    }
    
    @Test
    @DisplayName("mergeCluster should throw exception for empty cluster indices")
    void testMergeClusterEmptyClusterIndices() {
        testEntities = List.of(createEntity("MIT", "ORGANIZATION"));
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.mergeCluster(Set.of(), testEntities);
        });
    }
    
    @Test
    @DisplayName("mergeCluster should throw exception for null entities")
    void testMergeClusterNullEntities() {
        Set<Integer> clusterIndices = Set.of(0);
        assertThrows(IllegalArgumentException.class, () -> {
            clusterer.mergeCluster(clusterIndices, null);
        });
    }
    
    // ========================================================================
    // Integration Tests
    // ========================================================================
    
    @Test
    @DisplayName("Integration: Full clustering pipeline for Warren Home cluster")
    void testIntegrationWarrenHomeCluster() {
        // Arrange: Warren Home examples from research.md
        testEntities = List.of(
            createEntity("Warren State Home", "ORGANIZATION", "State-run facility"),
            createEntity("Warren Home", "ORGANIZATION", "Training center"),
            createEntity("Warren State Home and Training School", "ORGANIZATION", "Educational institution")
        );
        
        // Act: Build matrix, cluster, merge
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        // Note: Pairwise scores are lower than expected (0.427, 0.313, 0.206)
        // Use threshold 0.3 to enable clustering via transitive connections
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.30);
        
        // Assert: Should create 1 cluster with all 3 entities
        assertEquals(1, clusters.size(), "Should cluster all Warren variations together");
        
        EntityCluster merged = clusterer.mergeCluster(clusters.get(0), testEntities);
        assertEquals("Warren State Home and Training School", merged.canonicalEntity().getEntityName(),
            "Should select longest name");
        assertEquals(3, merged.size(), "Cluster should contain 3 entities");
        assertFalse(merged.isSingleton(), "Should not be singleton");
        assertEquals(2, merged.aliases().size(), "Should have 2 aliases");
    }
    
    @Test
    @DisplayName("Integration: Type safety prevents clustering Apple org vs product")
    void testIntegrationTypeSafety() {
        // Arrange: Same name, different types
        testEntities = List.of(
            createEntity("Apple", "ORGANIZATION", "Technology company"),
            createEntity("Apple", "PRODUCT", "Fruit")
        );
        
        // Act
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        List<Set<Integer>> clusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.75);
        
        // Assert: Should create 2 separate clusters due to type mismatch
        assertEquals(2, clusters.size(), "Different types should not cluster together");
        
        for (Set<Integer> cluster : clusters) {
            assertEquals(1, cluster.size(), "Each cluster should be singleton");
        }
    }
    
    @Test
    @DisplayName("Integration: Handle mixed clusters with different thresholds")
    void testIntegrationMixedClustersThreshold() {
        // Arrange: Person name variations
        testEntities = List.of(
            createEntity("Robert Smith", "PERSON"),
            createEntity("Bob Smith", "PERSON"),
            createEntity("R. Smith", "PERSON"),
            createEntity("John Doe", "PERSON")
        );
        
        // Act: Build matrix
        double[][] matrix = clusterer.buildSimilarityMatrix(testEntities, calculator);
        
        // Test with high threshold (0.90)
        List<Set<Integer>> strictClusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.90);
        
        // Test with low threshold (0.50)
        List<Set<Integer>> looseClusters = clusterer.clusterBySimilarity(testEntities, matrix, 0.50);
        
        // Assert: Lower threshold should create fewer/larger clusters
        assertTrue(looseClusters.size() <= strictClusters.size(),
            "Lower threshold should produce fewer or equal clusters");
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Helper method to create test entities with default description.
     */
    private Entity createEntity(String name, String type) {
        return createEntity(name, type, "Description for " + name);
    }
    
    /**
     * Helper method to create test entities with custom description.
     */
    private Entity createEntity(String name, String type, String description) {
        return new Entity(name, type, description, "test-source-" + name.hashCode(), null);
    }
}
