package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for resolving duplicate entities in the knowledge graph.
 * 
 * This service identifies entities that represent the same real-world concept
 * and merges them into canonical entities. It uses a multi-metric similarity
 * approach combining string matching, type comparison, and optional semantic
 * similarity.
 */
@ApplicationScoped
public class EntityResolver {
    
    private static final Logger logger = LoggerFactory.getLogger(EntityResolver.class);
    
    @Inject
    DeduplicationConfig config;
    
    @Inject
    EntitySimilarityCalculator calculator;
    
    @Inject
    EntityClusterer clusterer;
    
    /**
     * Resolves duplicate entities in a list.
     * 
     * Algorithm:
     * 1. Group entities by type (PERSON, ORGANIZATION, etc.)
     * 2. Within each type, compute pairwise similarity scores
     * 3. Apply clustering to identify groups of duplicate entities
     * 4. Merge each cluster into a canonical entity with aliases
     * 
     * @param entities List of entities to deduplicate (must not be null)
     * @param projectId Project ID for context (used for logging, can be null)
     * @return List of resolved entities with duplicates merged
     * @throws IllegalArgumentException if entities is null
     */
    public List<Entity> resolveDuplicates(List<Entity> entities, String projectId) {
        if (entities == null) {
            throw new IllegalArgumentException("entities cannot be null");
        }
        
        // Early return for empty input
        if (entities.isEmpty()) {
            return List.of();
        }
        
        // Check if resolution is enabled
        if (!config.enabled()) {
            logger.debug("Entity resolution is disabled");
            return entities;
        }
        
        try {
            logger.info("Resolving duplicates for {} entities (project={}, threshold={})", 
                       entities.size(), projectId, config.similarity().threshold());
            
            // Step 1: Group entities by type for type-based blocking optimization
            Map<String, List<Entity>> entitiesByType = groupEntitiesByType(entities);
            
            List<Entity> resolvedEntities = new ArrayList<>();
            
            // Step 2: Process each type group independently
            for (Map.Entry<String, List<Entity>> entry : entitiesByType.entrySet()) {
                String type = entry.getKey();
                List<Entity> typeEntities = entry.getValue();
                
                logger.debug("Processing {} entities of type '{}'", typeEntities.size(), type);
                
                // Step 3: Compute pairwise similarity matrix
                double[][] similarityMatrix = computeSimilarityMatrix(typeEntities);
                
                // Step 4: Cluster similar entities
                List<Set<Integer>> clusters = clusterer.clusterBySimilarity(
                    typeEntities, similarityMatrix, config.similarity().threshold()
                );
                
                logger.debug("Found {} clusters for type '{}'", clusters.size(), type);
                
                // Step 5: Merge each cluster into a canonical entity
                for (Set<Integer> cluster : clusters) {
                    EntityCluster mergedCluster = clusterer.mergeCluster(cluster, typeEntities);
                    resolvedEntities.add(mergedCluster.canonicalEntity());
                }
            }
            
            logger.info("Resolution complete: {} entities → {} entities (removed {} duplicates)", 
                       entities.size(), resolvedEntities.size(), 
                       entities.size() - resolvedEntities.size());
            
            return resolvedEntities;
            
        } catch (Exception e) {
            logger.error("Failed to resolve entities for project {}: {}", 
                        projectId, e.getMessage(), e);
            // Return unmodified entities as fallback
            return entities;
        }
    }
    
    /**
     * Resolves duplicate entities and returns detailed statistics.
     * 
     * This method provides the same functionality as resolveDuplicates() but
     * returns additional metrics about the resolution process.
     * 
     * @param entities List of entities to deduplicate (must not be null)
     * @param projectId Project ID for context (used for logging, can be null)
     * @return EntityResolutionResult containing resolved entities and statistics
     * @throws IllegalArgumentException if entities is null
     */
    public EntityResolutionResult resolveDuplicatesWithStats(List<Entity> entities, String projectId) {
        if (entities == null) {
            throw new IllegalArgumentException("entities cannot be null");
        }
        
        // Early return for empty input
        if (entities.isEmpty()) {
            return new EntityResolutionResult(
                List.of(), 0, 0, 0, 0, java.time.Duration.ZERO
            );
        }
        
        // Check if resolution is enabled
        if (!config.enabled()) {
            logger.debug("Entity resolution is disabled");
            return new EntityResolutionResult(
                entities, entities.size(), entities.size(), 0, 0, java.time.Duration.ZERO
            );
        }
        
        Instant startTime = Instant.now();
        int originalCount = entities.size();
        
        try {
            logger.info("Resolving duplicates with stats for {} entities (project={}, threshold={})", 
                       entities.size(), projectId, config.similarity().threshold());
            
            // Step 1: Group entities by type for type-based blocking optimization
            Map<String, List<Entity>> entitiesByType = groupEntitiesByType(entities);
            
            List<Entity> resolvedEntities = new ArrayList<>();
            int totalClusters = 0;
            
            // Step 2: Process each type group independently
            for (Map.Entry<String, List<Entity>> entry : entitiesByType.entrySet()) {
                String type = entry.getKey();
                List<Entity> typeEntities = entry.getValue();
                
                logger.debug("Processing {} entities of type '{}'", typeEntities.size(), type);
                
                // Step 3: Compute pairwise similarity matrix
                double[][] similarityMatrix = computeSimilarityMatrix(typeEntities);
                
                // Step 4: Cluster similar entities
                List<Set<Integer>> clusters = clusterer.clusterBySimilarity(
                    typeEntities, similarityMatrix, config.similarity().threshold()
                );
                
                totalClusters += clusters.size();
                logger.debug("Found {} clusters for type '{}'", clusters.size(), type);
                
                // Step 5: Merge each cluster into a canonical entity
                for (Set<Integer> cluster : clusters) {
                    EntityCluster mergedCluster = clusterer.mergeCluster(cluster, typeEntities);
                    resolvedEntities.add(mergedCluster.canonicalEntity());
                }
            }
            
            // Calculate statistics
            Instant endTime = Instant.now();
            Duration processingTime = Duration.between(startTime, endTime);
            int resolvedCount = resolvedEntities.size();
            int duplicatesRemoved = originalCount - resolvedCount;
            
            logger.info("Resolution complete: {} entities → {} entities (removed {} duplicates in {}ms)", 
                       originalCount, resolvedCount, duplicatesRemoved, processingTime.toMillis());
            
            return new EntityResolutionResult(
                resolvedEntities,
                originalCount,
                resolvedCount,
                duplicatesRemoved,
                totalClusters,
                processingTime
            );
            
        } catch (Exception e) {
            logger.error("Failed to resolve entities with stats for project {}: {}", 
                        projectId, e.getMessage(), e);
            
            // Return unmodified entities as fallback with timing
            Duration processingTime = Duration.between(startTime, Instant.now());
            return new EntityResolutionResult(
                entities, originalCount, originalCount, 0, 0, processingTime
            );
        }
    }
    
    /**
     * Groups entities by their type for type-based blocking optimization.
     * Entities with null or empty types are grouped under "UNKNOWN".
     */
    private Map<String, List<Entity>> groupEntitiesByType(List<Entity> entities) {
        Map<String, List<Entity>> grouped = new HashMap<>();
        
        for (Entity entity : entities) {
            String type = (entity.getEntityType() != null && !entity.getEntityType().isEmpty()) 
                ? entity.getEntityType() 
                : "UNKNOWN";
            
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(entity);
        }
        
        return grouped;
    }
    
    /**
     * Computes a pairwise similarity matrix for the given entities.
     * Matrix is symmetric: matrix[i][j] = matrix[j][i]
     */
    private double[][] computeSimilarityMatrix(List<Entity> entities) {
        int n = entities.size();
        double[][] matrix = new double[n][n];
        
        // Initialize diagonal to 1.0 (entity is 100% similar to itself)
        for (int i = 0; i < n; i++) {
            matrix[i][i] = 1.0;
        }
        
        // Compute upper triangle (and mirror to lower triangle)
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                EntitySimilarityScore score = calculator.computeSimilarity(
                    entities.get(i), entities.get(j)
                );
                
                double similarity = score.finalScore();
                matrix[i][j] = similarity;
                matrix[j][i] = similarity;  // Symmetric
            }
        }
        
        return matrix;
    }
}
