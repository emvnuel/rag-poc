package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes GLOBAL mode queries.
 * 
 * <p>GLOBAL mode utilizes global knowledge from entities and their relationships.
 * When keyword extraction is enabled, high-level (thematic) keywords are used for
 * relationship-focused retrieval, as per LightRAG spec.</p>
 * 
 * <h2>Search Strategy:</h2>
 * <ul>
 *   <li>With keyword extraction: Uses high-level keywords (themes, concepts) for search</li>
 *   <li>Without keyword extraction: Uses original query for entity similarity search</li>
 * </ul>
 */
public class GlobalQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    private final KeywordExtractor keywordExtractor;
    
    /**
     * Creates a GlobalQueryExecutor without keyword extraction (backward compatible).
     */
    public GlobalQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String systemPrompt
    ) {
        this(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage,
             entityVectorStorage, graphStorage, systemPrompt, null);
    }
    
    /**
     * Creates a GlobalQueryExecutor with optional keyword extraction.
     * 
     * @param llmFunction LLM function for generating responses
     * @param embeddingFunction Embedding function for vector search
     * @param chunkStorage KV storage for chunks
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for relationships
     * @param systemPrompt System prompt for LLM
     * @param keywordExtractor Optional keyword extractor (null to disable)
     */
    public GlobalQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String systemPrompt,
        @Nullable KeywordExtractor keywordExtractor
    ) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        this.systemPrompt = systemPrompt;
        this.keywordExtractor = keywordExtractor;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing GLOBAL query");
        
        // Step 1: Extract keywords (if enabled) or use original query
        CompletableFuture<String> searchQueryFuture = extractSearchQuery(query, param.getProjectId());
        
        return searchQueryFuture.thenCompose(searchQuery -> {
            // Step 2: Embed the search query (may be enhanced with keywords)
            return embeddingFunction.embedSingle(searchQuery)
                .thenCompose(queryEmbedding -> {
                    // Step 3: Search for similar entities with project filter
                    int topK = param.getTopK();
                    VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
                        "entity", 
                        null, 
                        param.getProjectId()
                    );
                    return entityVectorStorage.query(queryEmbedding, topK, filter);
                })
                .thenCompose(entityResults -> {
                    // Step 4: Extract entity names from results (content field contains entity name)
                    List<String> entityIds = entityResults.stream()
                        .map(result -> result.metadata().content())
                        .toList();
                    
                    if (entityIds.isEmpty()) {
                        return CompletableFuture.completedFuture(new LightRAGQueryResult(
                            "",
                            Collections.emptyList(),
                            QueryParam.Mode.GLOBAL,
                            0
                        ));
                    }
                    
                    // Step 5: Get entities and their relationships from graph
                    return graphStorage.getEntities(param.getProjectId(), entityIds).thenCompose((List<Entity> entities) -> {
                        // Get relations for each entity
                        List<CompletableFuture<List<Relation>>> relationFutures = new ArrayList<>();
                        for (String entityId : entityIds) {
                            relationFutures.add(graphStorage.getRelationsForEntity(param.getProjectId(), entityId));
                        }
                        
                        return CompletableFuture.allOf(relationFutures.toArray(new CompletableFuture[0]))
                            .thenCompose(v -> {
                                // Combine all relations
                                List<Relation> allRelations = relationFutures.stream()
                                    .flatMap(f -> f.join().stream())
                                    .distinct() // Remove duplicates
                                    .toList();
                                
                                // Format entities and relations as context WITHOUT citations
                                // Entities don't have document UUIDs, so they provide background context only
                                StringBuilder context = new StringBuilder();
                                
                                context.append("Entities:\n");
                                for (Entity entity : entities) {
                                    context.append(entity.getEntityName())
                                        .append(" (")
                                        .append(entity.getEntityType())
                                        .append("): ")
                                        .append(entity.getDescription())
                                        .append("\n");
                                }
                                
                                context.append("\nRelationships:\n");
                                for (Relation relation : allRelations) {
                                    context.append("- ")
                                        .append(relation.getSrcId())
                                        .append(" -> ")
                                        .append(relation.getTgtId())
                                        .append(": ")
                                        .append(relation.getDescription())
                                        .append("\n");
                                }
                                
                                // Convert entity results to source chunks
                                List<SourceChunk> sourceChunks = new ArrayList<>();
                                for (int i = 0; i < entityResults.size() && i < entities.size(); i++) {
                                    VectorStorage.VectorSearchResult result = entityResults.get(i);
                                    Entity entity = entities.get(i);
                                    
                                    sourceChunks.add(new SourceChunk(
                                        result.id(),                          // chunkId (entity ID)
                                        entity.getDescription(),              // content
                                        result.score(),                       // relevanceScore
                                        null,                                 // documentId (not applicable for entities)
                                        null,                                 // sourceId (not applicable)
                                        0,                                    // chunkIndex (not applicable)
                                        "entity"                              // type
                                    ));
                                }
                                
                                String contextStr = context.toString();
                                
                                // Step 6: Build prompt and call LLM
                                if (param.isOnlyNeedContext()) {
                                    return CompletableFuture.completedFuture(new LightRAGQueryResult(
                                        contextStr,
                                        sourceChunks,
                                        QueryParam.Mode.GLOBAL,
                                        sourceChunks.size()
                                    ));
                                }
                                
                                // Use original query for prompt (not keyword-enhanced)
                                String prompt = buildPrompt(query, contextStr, param);
                                
                                if (param.isOnlyNeedPrompt()) {
                                    return CompletableFuture.completedFuture(new LightRAGQueryResult(
                                        prompt,
                                        sourceChunks,
                                        QueryParam.Mode.GLOBAL,
                                        sourceChunks.size()
                                    ));
                                }
                                
                                return llmFunction.apply(prompt, systemPrompt)
                                    .thenApply(answer -> new LightRAGQueryResult(
                                        answer,
                                        sourceChunks,
                                        QueryParam.Mode.GLOBAL,
                                        sourceChunks.size()
                                    ));
                            });
                    });
                });
        });
    }
    
    /**
     * Extracts high-level keywords for search or falls back to original query.
     * 
     * <p>For GLOBAL mode, high-level keywords are preferred as they target
     * themes, concepts, and relationships rather than specific entities.</p>
     * 
     * @param query the original user query
     * @param projectId the project context
     * @return CompletableFuture with search query (keyword-enhanced or original)
     */
    private CompletableFuture<String> extractSearchQuery(@NotNull String query, @Nullable String projectId) {
        if (keywordExtractor == null) {
            logger.debug("Keyword extraction not available, using original query");
            return CompletableFuture.completedFuture(query);
        }
        
        return keywordExtractor.extract(query, projectId)
            .thenApply(keywords -> {
                List<String> highLevelKeywords = keywords.highLevelKeywords();
                
                if (highLevelKeywords.isEmpty()) {
                    logger.debug("No high-level keywords extracted, using original query");
                    return query;
                }
                
                // Build enhanced search query from high-level keywords
                // Combine original query with keywords for better coverage
                String keywordString = String.join(" ", highLevelKeywords);
                String enhancedQuery = query + " " + keywordString;
                
                logger.debug("Enhanced GLOBAL search query with {} keywords: {}", 
                    highLevelKeywords.size(), keywordString);
                
                return enhancedQuery;
            })
            .exceptionally(e -> {
                logger.warn("Keyword extraction failed, using original query: {}", e.getMessage());
                return query;
            });
    }
}
