package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
            Instant overallStart = Instant.now();
            
            logger.info("Resolving duplicates for {} entities (project={}, threshold={}, parallel={}, batch={})", 
                       entities.size(), projectId, config.similarity().threshold(),
                       config.parallel().enabled(), config.batch().size());
            
            // Step 1: Group entities by type for type-based blocking optimization
            Instant groupingStart = Instant.now();
            Map<String, List<Entity>> entitiesByType = groupEntitiesByType(entities);
            long groupingMs = Duration.between(groupingStart, Instant.now()).toMillis();
            
            logger.debug("Grouped entities into {} types in {}ms", entitiesByType.size(), groupingMs);
            
            List<Entity> resolvedEntities = new ArrayList<>();
            long totalSimilarityMs = 0;
            long totalClusteringMs = 0;
            long totalMergingMs = 0;
            
            // Step 2: Process each type group independently
            for (Map.Entry<String, List<Entity>> entry : entitiesByType.entrySet()) {
                String type = entry.getKey();
                List<Entity> typeEntities = entry.getValue();
                
                logger.debug("Processing {} entities of type '{}'", typeEntities.size(), type);
                
                // Step 3: Compute pairwise similarity matrix
                Instant similarityStart = Instant.now();
                double[][] similarityMatrix = computeSimilarityMatrix(typeEntities);
                long similarityMs = Duration.between(similarityStart, Instant.now()).toMillis();
                totalSimilarityMs += similarityMs;
                
                logger.debug("Computed {}x{} similarity matrix in {}ms", 
                            typeEntities.size(), typeEntities.size(), similarityMs);
                
                // Step 4: Cluster similar entities
                Instant clusteringStart = Instant.now();
                List<Set<Integer>> clusters = clusterer.clusterBySimilarity(
                    typeEntities, similarityMatrix, config.similarity().threshold()
                );
                long clusteringMs = Duration.between(clusteringStart, Instant.now()).toMillis();
                totalClusteringMs += clusteringMs;
                
                logger.debug("Found {} clusters for type '{}' in {}ms", 
                            clusters.size(), type, clusteringMs);
                
                // Step 5: Merge each cluster into a canonical entity
                Instant mergingStart = Instant.now();
                for (Set<Integer> cluster : clusters) {
                    EntityCluster mergedCluster = clusterer.mergeCluster(cluster, typeEntities);
                    resolvedEntities.add(mergedCluster.canonicalEntity());
                }
                long mergingMs = Duration.between(mergingStart, Instant.now()).toMillis();
                totalMergingMs += mergingMs;
            }
            
            long overallMs = Duration.between(overallStart, Instant.now()).toMillis();
            int duplicatesRemoved = entities.size() - resolvedEntities.size();
            double reductionPercent = (duplicatesRemoved * 100.0) / entities.size();
            
            logger.info("Resolution complete: {} entities → {} entities (removed {} duplicates, {:.1f}% reduction) in {}ms " +
                       "[grouping={}ms, similarity={}ms, clustering={}ms, merging={}ms]", 
                       entities.size(), resolvedEntities.size(), duplicatesRemoved, reductionPercent, overallMs,
                       groupingMs, totalSimilarityMs, totalClusteringMs, totalMergingMs);
            
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
            logger.info("Resolving duplicates with stats for {} entities (project={}, threshold={}, parallel={}, batch={})", 
                       entities.size(), projectId, config.similarity().threshold(),
                       config.parallel().enabled(), config.batch().size());
            
            // Step 1: Group entities by type for type-based blocking optimization
            Instant groupingStart = Instant.now();
            Map<String, List<Entity>> entitiesByType = groupEntitiesByType(entities);
            long groupingMs = Duration.between(groupingStart, Instant.now()).toMillis();
            
            logger.debug("Grouped entities into {} types in {}ms", entitiesByType.size(), groupingMs);
            
            List<Entity> resolvedEntities = new ArrayList<>();
            int totalClusters = 0;
            long totalSimilarityMs = 0;
            long totalClusteringMs = 0;
            long totalMergingMs = 0;
            
            // Step 2: Process each type group independently
            for (Map.Entry<String, List<Entity>> entry : entitiesByType.entrySet()) {
                String type = entry.getKey();
                List<Entity> typeEntities = entry.getValue();
                
                logger.debug("Processing {} entities of type '{}'", typeEntities.size(), type);
                
                // Step 3: Compute pairwise similarity matrix
                Instant similarityStart = Instant.now();
                double[][] similarityMatrix = computeSimilarityMatrix(typeEntities);
                long similarityMs = Duration.between(similarityStart, Instant.now()).toMillis();
                totalSimilarityMs += similarityMs;
                
                logger.debug("Computed {}x{} similarity matrix in {}ms", 
                            typeEntities.size(), typeEntities.size(), similarityMs);
                
                // Step 4: Cluster similar entities
                Instant clusteringStart = Instant.now();
                List<Set<Integer>> clusters = clusterer.clusterBySimilarity(
                    typeEntities, similarityMatrix, config.similarity().threshold()
                );
                long clusteringMs = Duration.between(clusteringStart, Instant.now()).toMillis();
                totalClusteringMs += clusteringMs;
                
                totalClusters += clusters.size();
                logger.debug("Found {} clusters for type '{}' in {}ms", 
                            clusters.size(), type, clusteringMs);
                
                // Step 5: Merge each cluster into a canonical entity
                Instant mergingStart = Instant.now();
                for (Set<Integer> cluster : clusters) {
                    EntityCluster mergedCluster = clusterer.mergeCluster(cluster, typeEntities);
                    resolvedEntities.add(mergedCluster.canonicalEntity());
                }
                long mergingMs = Duration.between(mergingStart, Instant.now()).toMillis();
                totalMergingMs += mergingMs;
            }
            
            // Calculate statistics
            Instant endTime = Instant.now();
            Duration processingTime = Duration.between(startTime, endTime);
            int resolvedCount = resolvedEntities.size();
            int duplicatesRemoved = originalCount - resolvedCount;
            double reductionPercent = (duplicatesRemoved * 100.0) / originalCount;
            
            logger.info("Resolution complete: {} entities → {} entities (removed {} duplicates, {:.1f}% reduction) in {}ms " +
                       "[grouping={}ms, similarity={}ms, clustering={}ms, merging={}ms]", 
                       originalCount, resolvedCount, duplicatesRemoved, reductionPercent, processingTime.toMillis(),
                       groupingMs, totalSimilarityMs, totalClusteringMs, totalMergingMs);
            
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
     * 
     * Supports parallel processing when enabled in configuration.
     */
    private double[][] computeSimilarityMatrix(List<Entity> entities) {
        int n = entities.size();
        double[][] matrix = new double[n][n];
        
        // Initialize diagonal to 1.0 (entity is 100% similar to itself)
        for (int i = 0; i < n; i++) {
            matrix[i][i] = 1.0;
        }
        
        // Use parallel processing if enabled and entity count justifies it
        boolean useParallel = config.parallel().enabled() && n > config.batch().size();
        
        if (useParallel) {
            computeSimilarityMatrixParallel(entities, matrix);
        } else {
            computeSimilarityMatrixSequential(entities, matrix);
        }
        
        return matrix;
    }
    
    /**
     * Sequential computation of similarity matrix (upper triangle).
     */
    private void computeSimilarityMatrixSequential(List<Entity> entities, double[][] matrix) {
        int n = entities.size();
        
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
    }
    
    /**
     * Parallel computation of similarity matrix using batch processing.
     * Divides the upper triangle into batches and processes them in parallel.
     */
    private void computeSimilarityMatrixParallel(List<Entity> entities, double[][] matrix) {
        int n = entities.size();
        int batchSize = config.batch().size();
        int numThreads = config.parallel().threads();
        
        // Create thread pool
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        try {
            // Collect all pairs to process (upper triangle)
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    pairs.add(new int[]{i, j});
                }
            }
            
            // Process pairs in batches
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int start = 0; start < pairs.size(); start += batchSize) {
                int end = Math.min(start + batchSize, pairs.size());
                List<int[]> batch = pairs.subList(start, end);
                
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    for (int[] pair : batch) {
                        int i = pair[0];
                        int j = pair[1];
                        
                        EntitySimilarityScore score = calculator.computeSimilarity(
                            entities.get(i), entities.get(j)
                        );
                        
                        double similarity = score.finalScore();
                        synchronized (matrix) {
                            matrix[i][j] = similarity;
                            matrix[j][i] = similarity;  // Symmetric
                        }
                    }
                }, executor);
                
                futures.add(future);
            }
            
            // Wait for all batches to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
        } finally {
            executor.shutdown();
        }
    }
}
