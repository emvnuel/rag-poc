package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for clustering entities based on similarity scores.
 * 
 * Uses threshold-based connected components algorithm to identify
 * groups of similar entities that should be merged.
 */
@ApplicationScoped
public class EntityClusterer {
    
    private static final Logger logger = LoggerFactory.getLogger(EntityClusterer.class);
    
    @Inject
    DeduplicationConfig config;
    
    /**
     * Clusters entities based on similarity matrix.
     * 
     * Algorithm (Threshold-Based Connected Components):
     * 1. Build similarity graph: nodes = entities, edges = similarity >= threshold
     * 2. Apply connected components algorithm (DFS/BFS)
     * 3. Each connected component becomes a cluster
     * 
     * @param entities List of entities to cluster (must not be null)
     * @param similarityMatrix Pairwise similarity scores (must be n×n where n = entities.size())
     * @param threshold Similarity threshold for considering entities duplicates [0.0, 1.0]
     * @return List of clusters, each containing entity indices that should be merged
     * @throws IllegalArgumentException if inputs are invalid
     */
    public List<Set<Integer>> clusterBySimilarity(
            List<Entity> entities,
            double[][] similarityMatrix,
            double threshold) {
        
        if (entities == null) {
            throw new IllegalArgumentException("entities cannot be null");
        }
        if (similarityMatrix == null) {
            throw new IllegalArgumentException("similarityMatrix cannot be null");
        }
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0.0, 1.0]");
        }
        
        int n = entities.size();
        if (n == 0) {
            return new java.util.ArrayList<>();
        }
        
        // Track which nodes have been visited
        boolean[] visited = new boolean[n];
        List<Set<Integer>> clusters = new java.util.ArrayList<>();
        
        // Find connected components using DFS
        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                Set<Integer> cluster = new java.util.HashSet<>();
                dfs(i, visited, cluster, similarityMatrix, threshold, n);
                clusters.add(cluster);
            }
        }
        
        return clusters;
    }
    
    /**
     * Depth-first search to find connected components.
     * 
     * @param node Current node index
     * @param visited Array tracking visited nodes
     * @param cluster Current cluster being built
     * @param similarityMatrix Similarity scores
     * @param threshold Similarity threshold
     * @param n Number of nodes
     */
    private void dfs(int node, boolean[] visited, Set<Integer> cluster, 
                     double[][] similarityMatrix, double threshold, int n) {
        visited[node] = true;
        cluster.add(node);
        
        // Visit all neighbors (entities with similarity >= threshold)
        for (int neighbor = 0; neighbor < n; neighbor++) {
            if (!visited[neighbor] && similarityMatrix[node][neighbor] >= threshold) {
                dfs(neighbor, visited, cluster, similarityMatrix, threshold, n);
            }
        }
    }
    
    /**
     * Merges a cluster of entities into a single canonical entity.
     * 
     * Merge strategy:
     * - Select canonical entity: longest name in cluster
     * - Combine descriptions: concatenate with " | " separator
     * - Extract aliases: all names except canonical
     * - Aggregate source IDs: union of all source IDs
     * 
     * @param clusterIndices Indices of entities in the cluster (must not be empty)
     * @param entities Original list of entities (must not be null)
     * @return EntityCluster with canonical entity and metadata
     * @throws IllegalArgumentException if clusterIndices is empty or entities is null
     */
    public EntityCluster mergeCluster(Set<Integer> clusterIndices, List<Entity> entities) {
        if (clusterIndices == null || clusterIndices.isEmpty()) {
            throw new IllegalArgumentException("clusterIndices cannot be null or empty");
        }
        if (entities == null) {
            throw new IllegalArgumentException("entities cannot be null");
        }
        
        // Step 1: Find canonical entity (longest name)
        Entity canonicalEntity = null;
        int maxNameLength = -1;
        
        for (int idx : clusterIndices) {
            Entity entity = entities.get(idx);
            String name = entity.getEntityName();
            if (name != null && name.length() > maxNameLength) {
                maxNameLength = name.length();
                canonicalEntity = entity;
            }
        }
        
        // Step 2: Extract aliases (all names except canonical)
        List<String> aliases = new ArrayList<>();
        String canonicalName = canonicalEntity.getEntityName();
        
        for (int idx : clusterIndices) {
            Entity entity = entities.get(idx);
            String name = entity.getEntityName();
            if (name != null && !name.equals(canonicalName)) {
                aliases.add(name);
            }
        }
        
        // Step 3: Merge descriptions with " | " separator
        StringBuilder mergedDescription = new StringBuilder();
        boolean first = true;
        
        for (int idx : clusterIndices) {
            Entity entity = entities.get(idx);
            String desc = entity.getDescription();
            if (desc != null && !desc.isBlank()) {
                if (!first) {
                    mergedDescription.append(" | ");
                }
                mergedDescription.append(desc);
                first = false;
            }
        }
        
        // Handle case where all descriptions are blank
        String finalDescription = mergedDescription.length() > 0 
            ? mergedDescription.toString() 
            : "No description available";
        
        // Step 4: Create EntityCluster
        return new EntityCluster(
            canonicalEntity,
            clusterIndices,
            aliases,
            finalDescription
        );
    }
    
    /**
     * Builds a similarity matrix for a list of entities.
     * 
     * This is a helper method that uses EntitySimilarityCalculator to
     * compute all pairwise similarities. Supports parallel processing when
     * enabled in configuration.
     * 
     * @param entities List of entities (must not be null)
     * @param calculator Similarity calculator (must not be null)
     * @return n×n similarity matrix where matrix[i][j] = similarity(i, j)
     * @throws IllegalArgumentException if any parameter is null
     */
    public double[][] buildSimilarityMatrix(
            List<Entity> entities,
            EntitySimilarityCalculator calculator) {
        
        if (entities == null) {
            throw new IllegalArgumentException("entities cannot be null");
        }
        if (calculator == null) {
            throw new IllegalArgumentException("calculator cannot be null");
        }
        
        int n = entities.size();
        double[][] matrix = new double[n][n];
        
        // Initialize diagonal
        for (int i = 0; i < n; i++) {
            matrix[i][i] = 1.0;
        }
        
        // Use parallel processing if enabled and entity count justifies it
        boolean useParallel = config.parallel().enabled() && n > config.batch().size();
        
        if (useParallel) {
            buildSimilarityMatrixParallel(entities, calculator, matrix);
        } else {
            buildSimilarityMatrixSequential(entities, calculator, matrix);
        }
        
        return matrix;
    }
    
    /**
     * Sequential computation of similarity matrix.
     */
    private void buildSimilarityMatrixSequential(
            List<Entity> entities,
            EntitySimilarityCalculator calculator,
            double[][] matrix) {
        
        int n = entities.size();
        
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    EntitySimilarityScore score = calculator.computeSimilarity(
                        entities.get(i), 
                        entities.get(j)
                    );
                    matrix[i][j] = score.finalScore();
                }
            }
        }
    }
    
    /**
     * Parallel computation of similarity matrix using batch processing.
     */
    private void buildSimilarityMatrixParallel(
            List<Entity> entities,
            EntitySimilarityCalculator calculator,
            double[][] matrix) {
        
        int n = entities.size();
        int batchSize = config.batch().size();
        int numThreads = config.parallel().threads();
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        try {
            // Collect all pairs to process (excluding diagonal)
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        pairs.add(new int[]{i, j});
                    }
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
                            entities.get(i), 
                            entities.get(j)
                        );
                        
                        synchronized (matrix) {
                            matrix[i][j] = score.finalScore();
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
