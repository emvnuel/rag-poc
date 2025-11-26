package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Chunk selector using weighted polling based on entity/relation connections.
 * 
 * <p>This strategy combines vector similarity with entity-based weighting to
 * prioritize chunks that are connected to entities relevant to the query.</p>
 * 
 * <h2>Strategy:</h2>
 * <ol>
 *   <li>Perform initial vector similarity search (2x topK)</li>
 *   <li>For chunks containing relevant entities, boost their scores</li>
 *   <li>Apply weights from SelectionContext if provided</li>
 *   <li>Re-rank and return top K chunks</li>
 * </ol>
 * 
 * <h2>Weight Calculation:</h2>
 * <pre>
 * finalScore = vectorScore * (1 + entityBoost + relationBoost)
 * 
 * where:
 *   entityBoost = 0.3 if chunk contains entity from context
 *   relationBoost = 0.2 if chunk contains relation keyword from context
 * </pre>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class WeightedChunkSelector implements ChunkSelector {
    
    private static final Logger logger = LoggerFactory.getLogger(WeightedChunkSelector.class);
    private static final String STRATEGY_NAME = "weighted";
    
    /**
     * Score boost for chunks containing relevant entities.
     */
    private static final double ENTITY_BOOST = 0.3;
    
    /**
     * Score boost for chunks containing relation keywords.
     */
    private static final double RELATION_KEYWORD_BOOST = 0.2;
    
    /**
     * Multiplier for initial search to have enough candidates for re-ranking.
     */
    private static final int SEARCH_MULTIPLIER = 2;
    
    private final VectorStorage chunkVectorStorage;
    private final GraphStorage graphStorage;
    
    /**
     * Default constructor for CDI proxy.
     */
    public WeightedChunkSelector() {
        this.chunkVectorStorage = null;
        this.graphStorage = null;
    }
    
    /**
     * Creates a WeightedChunkSelector with the given storages.
     * 
     * @param chunkVectorStorage The vector storage for chunk embeddings
     * @param graphStorage The graph storage for entity lookups
     */
    @Inject
    public WeightedChunkSelector(VectorStorage chunkVectorStorage, GraphStorage graphStorage) {
        this.chunkVectorStorage = chunkVectorStorage;
        this.graphStorage = graphStorage;
    }
    
    @Override
    public CompletableFuture<List<ScoredChunk>> selectChunks(
            @NotNull Object queryEmbedding,
            @NotNull String projectId,
            int topK,
            SelectionContext context) {
        
        logger.debug("Selecting chunks using weighted polling, topK={}, projectId={}", topK, projectId);
        
        if (chunkVectorStorage == null) {
            logger.warn("ChunkVectorStorage not initialized, returning empty list");
            return CompletableFuture.completedFuture(List.of());
        }
        
        // Search for more candidates than needed for re-ranking
        int searchCount = topK * SEARCH_MULTIPLIER;
        
        VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
            "chunk",
            null,
            projectId
        );
        
        return chunkVectorStorage.query(queryEmbedding, searchCount, filter)
            .thenCompose(results -> {
                logger.debug("Vector search returned {} candidates for weighted selection", results.size());
                
                if (results.isEmpty() || context == null) {
                    // No context for weighting, just convert and return
                    return CompletableFuture.completedFuture(
                        results.stream()
                            .limit(topK)
                            .map(ScoredChunk::fromVectorResult)
                            .toList()
                    );
                }
                
                // Build entity source chunk map if graph storage is available
                return buildEntityChunkMap(projectId, context.entityNames())
                    .thenApply(entityChunkMap -> {
                        // Apply weighted scoring
                        List<WeightedResult> weightedResults = results.stream()
                            .map(result -> applyWeights(result, context, entityChunkMap))
                            .sorted(Comparator.comparingDouble(WeightedResult::weightedScore).reversed())
                            .limit(topK)
                            .toList();
                        
                        logger.debug("Weighted selection complete, returning {} chunks", weightedResults.size());
                        
                        return weightedResults.stream()
                            .map(wr -> new ScoredChunk(
                                wr.result.id(),
                                wr.result.metadata().content() != null ? wr.result.metadata().content() : "",
                                wr.weightedScore,
                                wr.result.metadata().documentId(),
                                wr.result.metadata().chunkIndex() != null ? wr.result.metadata().chunkIndex() : 0
                            ))
                            .toList();
                    });
            });
    }
    
    /**
     * Builds a map of entity names to their source chunk IDs.
     */
    private CompletableFuture<Map<String, Set<String>>> buildEntityChunkMap(
            String projectId, 
            List<String> entityNames) {
        
        if (graphStorage == null || entityNames == null || entityNames.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        return graphStorage.getEntities(projectId, entityNames)
            .thenApply(entities -> {
                Map<String, Set<String>> chunkMap = new HashMap<>();
                
                for (Entity entity : entities) {
                    Set<String> chunkIds = new HashSet<>(entity.getSourceChunkIds());
                    chunkMap.put(entity.getEntityName().toLowerCase(), chunkIds);
                }
                
                return chunkMap;
            })
            .exceptionally(e -> {
                logger.warn("Failed to build entity chunk map: {}", e.getMessage());
                return Map.of();
            });
    }
    
    /**
     * Applies weights to a vector search result based on context.
     */
    private WeightedResult applyWeights(
            VectorStorage.VectorSearchResult result,
            SelectionContext context,
            Map<String, Set<String>> entityChunkMap) {
        
        double baseScore = result.score();
        double boost = 0.0;
        
        String chunkId = result.id();
        String content = result.metadata().content();
        String contentLower = content != null ? content.toLowerCase() : "";
        
        // Check if chunk is a source for any relevant entity
        for (Map.Entry<String, Set<String>> entry : entityChunkMap.entrySet()) {
            if (entry.getValue().contains(chunkId)) {
                boost += ENTITY_BOOST;
                logger.trace("Chunk {} boosted by {} for entity {}", chunkId, ENTITY_BOOST, entry.getKey());
                break; // Only count once
            }
        }
        
        // Check if chunk content contains entity names
        if (context.entityNames() != null) {
            for (String entityName : context.entityNames()) {
                if (contentLower.contains(entityName.toLowerCase())) {
                    boost += ENTITY_BOOST * 0.5; // Partial boost for content match
                    break;
                }
            }
        }
        
        // Check if chunk content contains relation keywords
        if (context.relationKeywords() != null) {
            for (String keyword : context.relationKeywords()) {
                if (contentLower.contains(keyword.toLowerCase())) {
                    boost += RELATION_KEYWORD_BOOST;
                    break;
                }
            }
        }
        
        // Apply custom weights from context
        if (context.entityChunkWeights() != null) {
            Double customWeight = context.entityChunkWeights().get(chunkId);
            if (customWeight != null) {
                boost += customWeight;
            }
        }
        
        double weightedScore = baseScore * (1.0 + boost);
        
        return new WeightedResult(result, weightedScore);
    }
    
    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }
    
    /**
     * Internal record for tracking weighted results.
     */
    private record WeightedResult(
        VectorStorage.VectorSearchResult result,
        double weightedScore
    ) {}
}
