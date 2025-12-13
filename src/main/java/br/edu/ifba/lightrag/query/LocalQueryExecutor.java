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

import java.util.ArrayList;
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
 * 
 * <h2>Chunk Selection Strategy:</h2>
 * <ul>
 *   <li>VECTOR: Pure vector similarity search (default)</li>
 *   <li>WEIGHTED: Boosts chunks connected to relevant entities</li>
 * </ul>
 */
public class LocalQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    private final KeywordExtractor keywordExtractor;
    private final ChunkSelector chunkSelector;
    
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
             entityVectorStorage, graphStorage, systemPrompt, null, null);
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
        this(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage,
             entityVectorStorage, graphStorage, systemPrompt, keywordExtractor, null);
    }
    
    /**
     * Creates a LocalQueryExecutor with optional keyword extraction and chunk selector.
     * 
     * @param llmFunction LLM function for generating responses
     * @param embeddingFunction Embedding function for vector search
     * @param chunkStorage KV storage for chunks
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for relationships
     * @param systemPrompt System prompt for LLM
     * @param keywordExtractor Optional keyword extractor (null to disable)
     * @param chunkSelector Optional chunk selector (null to use direct vector query)
     */
    public LocalQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String systemPrompt,
        @Nullable KeywordExtractor keywordExtractor,
        @Nullable ChunkSelector chunkSelector
    ) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        this.systemPrompt = systemPrompt;
        this.keywordExtractor = keywordExtractor;
        this.chunkSelector = chunkSelector;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing LOCAL query with chunk selection strategy: {}", 
            param.getChunkSelectionStrategy());
        
        // Step 1: Extract keywords (if enabled) or use original query
        CompletableFuture<KeywordResult> keywordsFuture = extractKeywords(query, param.getProjectId());
        
        return keywordsFuture.thenCompose(keywordResult -> {
            String searchQuery = keywordResult.enhancedQuery;
            List<String> entityKeywords = keywordResult.lowLevelKeywords;
            
            // Step 2: Embed the search query (may be enhanced with keywords)
            return embeddingFunction.embedSingle(searchQuery)
                .thenCompose(queryEmbedding -> {
                    // Step 3: Select chunks based on strategy
                    return selectChunks(queryEmbedding, query, entityKeywords, param);
                })
                .thenCompose(scoredChunks -> {
                    // Step 4: Build context and prompt with citations
                    String context = formatScoredChunkContext(scoredChunks);
                    List<LightRAGQueryResult.SourceChunk> sourceChunks = convertScoredChunksToSource(scoredChunks);
                    
                    if (param.isOnlyNeedContext()) {
                        // Return only context (for compatibility)
                        return CompletableFuture.completedFuture(new LightRAGQueryResult(
                            context,
                            sourceChunks,
                            QueryParam.Mode.LOCAL,
                            sourceChunks.size()
                        ));
                    }
                    
                    // Use original query for prompt (not keyword-enhanced)
                    String prompt = buildPrompt(query, context, param);
                    
                    if (param.isOnlyNeedPrompt()) {
                        // Return only prompt (for compatibility)
                        return CompletableFuture.completedFuture(new LightRAGQueryResult(
                            prompt,
                            sourceChunks,
                            QueryParam.Mode.LOCAL,
                            sourceChunks.size()
                        ));
                    }
                    
                    // Step 5: Call LLM with context
                    return llmFunction.apply(prompt, systemPrompt)
                        .thenApply(answer -> new LightRAGQueryResult(
                            answer,
                            sourceChunks,
                            QueryParam.Mode.LOCAL,
                            sourceChunks.size()
                        ));
                });
        });
    }
    
    /**
     * Selects chunks using the configured strategy.
     * 
     * <p>If a ChunkSelector is provided and strategy is WEIGHTED, uses the selector.
     * Otherwise, falls back to direct vector storage query.</p>
     */
    private CompletableFuture<List<ChunkSelector.ScoredChunk>> selectChunks(
        @NotNull Object queryEmbedding,
        @NotNull String originalQuery,
        @NotNull List<String> entityKeywords,
        @NotNull QueryParam param
    ) {
        int topK = param.getChunkTopK();
        String projectId = param.getProjectId();
        
        // Use ChunkSelector if available
        if (chunkSelector != null) {
            // Build selection context for weighted selection
            ChunkSelector.SelectionContext context = new ChunkSelector.SelectionContext(
                originalQuery,
                entityKeywords,
                List.of(), // No relation keywords in LOCAL mode
                java.util.Map.of()
            );
            
            logger.debug("Using {} chunk selector with {} entity keywords", 
                chunkSelector.getStrategyName(), entityKeywords.size());
            
            return chunkSelector.selectChunks(queryEmbedding, projectId, topK, context);
        }
        
        // Fallback to direct vector query
        logger.debug("Using direct vector query (no chunk selector)");
        VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
            "chunk", 
            null, 
            projectId
        );
        
        return chunkVectorStorage.query(queryEmbedding, topK, filter)
            .thenApply(results -> results.stream()
                .map(ChunkSelector.ScoredChunk::fromVectorResult)
                .toList());
    }
    
    /**
     * Formats scored chunks into context string with citations.
     */
    private String formatScoredChunkContext(@NotNull List<ChunkSelector.ScoredChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (ChunkSelector.ScoredChunk chunk : chunks) {
            if (chunk.documentId() != null) {
                context.append(String.format("[%s] %s\n\n", chunk.documentId(), chunk.content()));
            } else {
                context.append(chunk.content()).append("\n\n");
            }
        }
        return context.toString();
    }
    
    /**
     * Converts scored chunks to source chunks for query result.
     */
    private List<LightRAGQueryResult.SourceChunk> convertScoredChunksToSource(
        @NotNull List<ChunkSelector.ScoredChunk> scoredChunks
    ) {
        List<LightRAGQueryResult.SourceChunk> sources = new ArrayList<>();
        for (ChunkSelector.ScoredChunk chunk : scoredChunks) {
            sources.add(new LightRAGQueryResult.SourceChunk(
                chunk.chunkId(),
                chunk.content(),
                chunk.score(),
                chunk.documentId(),
                chunk.chunkId(),
                chunk.chunkIndex(),
                "chunk"
            ));
        }
        return sources;
    }
    
    /**
     * Extracts keywords from query and returns both enhanced query and keyword list.
     * 
     * @param query the original user query
     * @param projectId the project context
     * @return CompletableFuture with keyword result
     */
    private CompletableFuture<KeywordResult> extractKeywords(@NotNull String query, @Nullable String projectId) {
        if (keywordExtractor == null) {
            logger.debug("Keyword extraction not available, using original query");
            return CompletableFuture.completedFuture(new KeywordResult(query, List.of()));
        }
        
        return keywordExtractor.extract(query, projectId)
            .thenApply(keywords -> {
                List<String> lowLevelKeywords = keywords.lowLevelKeywords();
                
                if (lowLevelKeywords.isEmpty()) {
                    logger.debug("No low-level keywords extracted, using original query");
                    return new KeywordResult(query, List.of());
                }
                
                // Build enhanced search query from low-level keywords
                String keywordString = String.join(" ", lowLevelKeywords);
                String enhancedQuery = query + " " + keywordString;
                
                logger.debug("Enhanced LOCAL search query with {} keywords: {}", 
                    lowLevelKeywords.size(), keywordString);
                
                return new KeywordResult(enhancedQuery, lowLevelKeywords);
            })
            .exceptionally(e -> {
                logger.warn("Keyword extraction failed, using original query: {}", e.getMessage());
                return new KeywordResult(query, List.of());
            });
    }
    
    /**
     * Result of keyword extraction.
     */
    private record KeywordResult(String enhancedQuery, List<String> lowLevelKeywords) {}
}
