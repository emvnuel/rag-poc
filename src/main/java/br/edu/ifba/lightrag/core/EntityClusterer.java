package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * compute all pairwise similarities.
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
        
        // Compute pairwise similarities
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    // Diagonal: entity compared to itself
                    matrix[i][j] = 1.0;
                } else {
                    // Off-diagonal: compute similarity
                    EntitySimilarityScore score = calculator.computeSimilarity(
                        entities.get(i), 
                        entities.get(j)
                    );
                    matrix[i][j] = score.finalScore();
                }
            }
        }
        
        return matrix;
    }
}
