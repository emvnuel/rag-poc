package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.query.KeywordExtractor;
import br.edu.ifba.lightrag.query.KeywordResult;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline stage that searches for relevant entities and their relations.
 * 
 * <p>This stage:</p>
 * <ol>
 *   <li>Optionally extracts high-level keywords from the query</li>
 *   <li>Searches entity vector storage for similar entities</li>
 *   <li>Retrieves entity details from graph storage</li>
 *   <li>Optionally retrieves relations for found entities</li>
 *   <li>Stores results in context as entity and relation candidates</li>
 * </ol>
 * 
 * <h2>Keyword Enhancement:</h2>
 * <p>When keyword extraction is enabled, high-level keywords (themes, concepts)
 * are used to enhance the search query for GLOBAL mode retrieval.</p>
 * 
 * @since spec-008
 */
public class EntitySearchStage implements PipelineStage {
    
    private static final Logger logger = LoggerFactory.getLogger(EntitySearchStage.class);
    private static final String STAGE_NAME = "entity-search";
    
    private final VectorStorage entityVectorStorage;
    private final GraphStorage graphStorage;
    private final EmbeddingFunction embeddingFunction;
    @Nullable
    private final KeywordExtractor keywordExtractor;
    private final boolean includeRelations;
    
    /**
     * Creates an EntitySearchStage without keyword extraction.
     */
    public EntitySearchStage(
            @NotNull VectorStorage entityVectorStorage,
            @NotNull GraphStorage graphStorage,
            @NotNull EmbeddingFunction embeddingFunction) {
        this(entityVectorStorage, graphStorage, embeddingFunction, null, true);
    }
    
    /**
     * Creates an EntitySearchStage with full configuration.
     *
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for entity/relation data
     * @param embeddingFunction Function to compute query embeddings
     * @param keywordExtractor Optional keyword extractor for query enhancement
     * @param includeRelations Whether to fetch relations for found entities
     */
    public EntitySearchStage(
            @NotNull VectorStorage entityVectorStorage,
            @NotNull GraphStorage graphStorage,
            @NotNull EmbeddingFunction embeddingFunction,
            @Nullable KeywordExtractor keywordExtractor,
            boolean includeRelations) {
        this.entityVectorStorage = entityVectorStorage;
        this.graphStorage = graphStorage;
        this.embeddingFunction = embeddingFunction;
        this.keywordExtractor = keywordExtractor;
        this.includeRelations = includeRelations;
    }
    
    @Override
    public CompletableFuture<PipelineContext> process(@NotNull PipelineContext context) {
        String query = context.getQuery();
        String projectId = context.getProjectId();
        int topK = context.getParam().getTopK();
        
        logger.debug("Starting entity search, topK={}, projectId={}", topK, projectId);
        
        // Step 1: Extract keywords if extractor is available
        CompletableFuture<String> searchQueryFuture = extractSearchQuery(query, projectId, context);
        
        return searchQueryFuture.thenCompose(searchQuery -> {
            // Step 2: Get or compute query embedding
            Object existingEmbedding = context.getQueryEmbedding();
            CompletableFuture<Object> embeddingFuture;
            
            if (existingEmbedding != null) {
                embeddingFuture = CompletableFuture.completedFuture(existingEmbedding);
            } else {
                embeddingFuture = embeddingFunction.embedSingle(searchQuery)
                        .thenApply(embedding -> (Object) embedding);
            }
            
            return embeddingFuture.thenCompose(queryEmbedding -> {
                // Step 3: Search for similar entities
                VectorFilter filter = new VectorFilter("entity", null, projectId);
                
                return entityVectorStorage.query(queryEmbedding, topK, filter)
                        .thenCompose(results -> {
                            if (results.isEmpty()) {
                                logger.debug("No entity search results found");
                                return CompletableFuture.completedFuture(context);
                            }
                            
                            // Extract entity names from vector results
                            List<String> entityNames = results.stream()
                                    .map(r -> r.metadata().content())
                                    .filter(name -> name != null && !name.isEmpty())
                                    .toList();
                            
                            logger.debug("Found {} entity candidates", entityNames.size());
                            
                            // Step 4: Get full entity details from graph
                            return graphStorage.getEntities(projectId, entityNames)
                                    .thenCompose(entities -> {
                                        context.setEntityCandidates(entities);
                                        
                                        // Step 5: Optionally get relations
                                        if (includeRelations && !entities.isEmpty()) {
                                            return fetchRelations(projectId, entityNames, context);
                                        }
                                        return CompletableFuture.completedFuture(context);
                                    });
                        });
            });
        });
    }
    
    /**
     * Fetches relations for the given entities.
     */
    private CompletableFuture<PipelineContext> fetchRelations(
            @NotNull String projectId,
            @NotNull List<String> entityNames,
            @NotNull PipelineContext context) {
        
        // Fetch relations for all entities in parallel
        List<CompletableFuture<List<Relation>>> relationFutures = entityNames.stream()
                .map(name -> graphStorage.getRelationsForEntity(projectId, name))
                .toList();
        
        return CompletableFuture.allOf(relationFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Combine and deduplicate relations
                    Set<String> seen = new HashSet<>();
                    List<Relation> allRelations = new ArrayList<>();
                    
                    for (CompletableFuture<List<Relation>> future : relationFutures) {
                        for (Relation relation : future.join()) {
                            String key = relation.getSrcId() + "->" + relation.getTgtId();
                            if (seen.add(key)) {
                                allRelations.add(relation);
                            }
                        }
                    }
                    
                    context.setRelationCandidates(allRelations);
                    logger.debug("Found {} unique relations", allRelations.size());
                    
                    return context;
                });
    }
    
    /**
     * Extracts high-level keywords and enhances the search query.
     */
    private CompletableFuture<String> extractSearchQuery(
            @NotNull String query,
            @NotNull String projectId,
            @NotNull PipelineContext context) {
        
        if (keywordExtractor == null) {
            logger.debug("Keyword extraction not available, using original query");
            return CompletableFuture.completedFuture(query);
        }
        
        // Check if keywords were already extracted (by a previous stage)
        KeywordResult existing = context.getKeywords();
        if (existing != null) {
            return CompletableFuture.completedFuture(
                    buildEnhancedQuery(query, existing.highLevelKeywords()));
        }
        
        return keywordExtractor.extract(query, projectId)
                .thenApply(keywords -> {
                    // Store keywords in context for other stages
                    context.setKeywords(keywords);
                    
                    List<String> highLevelKeywords = keywords.highLevelKeywords();
                    if (highLevelKeywords.isEmpty()) {
                        logger.debug("No high-level keywords extracted, using original query");
                        return query;
                    }
                    
                    return buildEnhancedQuery(query, highLevelKeywords);
                })
                .exceptionally(e -> {
                    logger.warn("Keyword extraction failed: {}", e.getMessage());
                    return query;
                });
    }
    
    /**
     * Builds an enhanced query by appending keywords.
     */
    private String buildEnhancedQuery(@NotNull String query, @NotNull List<String> keywords) {
        if (keywords.isEmpty()) {
            return query;
        }
        
        String keywordString = String.join(" ", keywords);
        logger.debug("Enhanced search query with {} keywords: {}", keywords.size(), keywordString);
        return query + " " + keywordString;
    }
    
    @Override
    public String getName() {
        return STAGE_NAME;
    }
}
