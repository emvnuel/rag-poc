package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes LOCAL mode queries.
 * 
 * <p>LOCAL mode focuses on context-dependent information using vector similarity search.
 * When keyword extraction is enabled, low-level (entity) keywords are used for more
 * precise entity retrieval, as per LightRAG spec.</p>
 * 
 * <h2>Search Strategy:</h2>
 * <ul>
 *   <li>With keyword extraction: Uses low-level keywords (specific entities) for search</li>
 *   <li>Without keyword extraction: Uses original query for similarity search</li>
 * </ul>
 */
public class LocalQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    private final KeywordExtractor keywordExtractor;
    
    /**
     * Creates a LocalQueryExecutor without keyword extraction (backward compatible).
     */
    public LocalQueryExecutor(
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
     * Creates a LocalQueryExecutor with optional keyword extraction.
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
    public LocalQueryExecutor(
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
        logger.info("Executing LOCAL query");
        
        // Step 1: Extract keywords (if enabled) or use original query
        CompletableFuture<String> searchQueryFuture = extractSearchQuery(query, param.getProjectId());
        
        return searchQueryFuture.thenCompose(searchQuery -> {
            // Step 2: Embed the search query (may be enhanced with keywords)
            return embeddingFunction.embedSingle(searchQuery)
                .thenCompose(queryEmbedding -> {
                    // Step 3: Search for similar chunks with project filter
                    int topK = param.getChunkTopK();
                    VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
                        "chunk", 
                        null, 
                        param.getProjectId()
                    );
                    return chunkVectorStorage.query(queryEmbedding, topK, filter);
                })
                .thenCompose(results -> {
                    // Step 4: Build context and prompt with citations
                    String context = formatChunkContextWithCitations(results);
                    
                    if (param.isOnlyNeedContext()) {
                        // Return only context (for compatibility)
                        return CompletableFuture.completedFuture(new LightRAGQueryResult(
                            context,
                            convertToSourceChunks(results),
                            QueryParam.Mode.LOCAL,
                            results.size()
                        ));
                    }
                    
                    // Use original query for prompt (not keyword-enhanced)
                    String prompt = buildPrompt(query, context, param);
                    
                    if (param.isOnlyNeedPrompt()) {
                        // Return only prompt (for compatibility)
                        return CompletableFuture.completedFuture(new LightRAGQueryResult(
                            prompt,
                            convertToSourceChunks(results),
                            QueryParam.Mode.LOCAL,
                            results.size()
                        ));
                    }
                    
                    // Step 5: Call LLM with context
                    return llmFunction.apply(prompt, systemPrompt)
                        .thenApply(answer -> new LightRAGQueryResult(
                            answer,
                            convertToSourceChunks(results),
                            QueryParam.Mode.LOCAL,
                            results.size()
                        ));
                });
        });
    }
    
    /**
     * Extracts low-level keywords for search or falls back to original query.
     * 
     * <p>For LOCAL mode, low-level keywords are preferred as they target
     * specific entities mentioned in the query.</p>
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
                List<String> lowLevelKeywords = keywords.lowLevelKeywords();
                
                if (lowLevelKeywords.isEmpty()) {
                    logger.debug("No low-level keywords extracted, using original query");
                    return query;
                }
                
                // Build enhanced search query from low-level keywords
                // Combine original query with keywords for better coverage
                String keywordString = String.join(" ", lowLevelKeywords);
                String enhancedQuery = query + " " + keywordString;
                
                logger.debug("Enhanced LOCAL search query with {} keywords: {}", 
                    lowLevelKeywords.size(), keywordString);
                
                return enhancedQuery;
            })
            .exceptionally(e -> {
                logger.warn("Keyword extraction failed, using original query: {}", e.getMessage());
                return query;
            });
    }
}
