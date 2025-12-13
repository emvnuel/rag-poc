package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.query.KeywordExtractor;
import br.edu.ifba.lightrag.query.KeywordResult;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline stage that searches for relevant chunks using vector similarity.
 * 
 * <p>This stage:</p>
 * <ol>
 *   <li>Optionally extracts keywords from the query</li>
 *   <li>Computes query embedding (if not already provided)</li>
 *   <li>Searches chunk vector storage for similar chunks</li>
 *   <li>Stores results in context as chunk candidates</li>
 * </ol>
 * 
 * <h2>Keyword Enhancement:</h2>
 * <p>When keyword extraction is enabled, low-level keywords are used to enhance
 * the search query for more precise retrieval.</p>
 * 
 * @since spec-008
 */
public class ChunkSearchStage implements PipelineStage {
    
    private static final Logger logger = LoggerFactory.getLogger(ChunkSearchStage.class);
    private static final String STAGE_NAME = "chunk-search";
    
    private final VectorStorage chunkVectorStorage;
    private final EmbeddingFunction embeddingFunction;
    @Nullable
    private final KeywordExtractor keywordExtractor;
    
    /**
     * Creates a ChunkSearchStage without keyword extraction.
     */
    public ChunkSearchStage(
            @NotNull VectorStorage chunkVectorStorage,
            @NotNull EmbeddingFunction embeddingFunction) {
        this(chunkVectorStorage, embeddingFunction, null);
    }
    
    /**
     * Creates a ChunkSearchStage with optional keyword extraction.
     *
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param embeddingFunction Function to compute query embeddings
     * @param keywordExtractor Optional keyword extractor for query enhancement
     */
    public ChunkSearchStage(
            @NotNull VectorStorage chunkVectorStorage,
            @NotNull EmbeddingFunction embeddingFunction,
            @Nullable KeywordExtractor keywordExtractor) {
        this.chunkVectorStorage = chunkVectorStorage;
        this.embeddingFunction = embeddingFunction;
        this.keywordExtractor = keywordExtractor;
    }
    
    @Override
    public CompletableFuture<PipelineContext> process(@NotNull PipelineContext context) {
        String query = context.getQuery();
        String projectId = context.getProjectId();
        int topK = context.getParam().getChunkTopK();
        
        logger.debug("Starting chunk search, topK={}, projectId={}", topK, projectId);
        
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
                // Step 3: Search for similar chunks
                VectorFilter filter = new VectorFilter("chunk", null, projectId);
                
                return chunkVectorStorage.query(queryEmbedding, topK, filter)
                        .thenApply(results -> {
                            // Step 4: Convert to SourceChunks and store in context
                            List<SourceChunk> chunks = convertToSourceChunks(results);
                            context.setChunkCandidates(chunks);
                            
                            logger.debug("Chunk search found {} results", chunks.size());
                            return context;
                        });
            });
        });
    }
    
    /**
     * Extracts low-level keywords and enhances the search query.
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
            return CompletableFuture.completedFuture(buildEnhancedQuery(query, existing.lowLevelKeywords()));
        }
        
        return keywordExtractor.extract(query, projectId)
                .thenApply(keywords -> {
                    // Store keywords in context for other stages
                    context.setKeywords(keywords);
                    
                    List<String> lowLevelKeywords = keywords.lowLevelKeywords();
                    if (lowLevelKeywords.isEmpty()) {
                        logger.debug("No low-level keywords extracted, using original query");
                        return query;
                    }
                    
                    return buildEnhancedQuery(query, lowLevelKeywords);
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
    
    /**
     * Converts vector search results to SourceChunk objects.
     */
    private List<SourceChunk> convertToSourceChunks(@NotNull List<VectorSearchResult> results) {
        return results.stream()
                .map(result -> {
                    VectorStorage.VectorMetadata metadata = result.metadata();
                    return new SourceChunk(
                            result.id(),
                            metadata.content() != null ? metadata.content() : "",
                            result.score(),
                            metadata.documentId(),
                            metadata.documentId(),
                            metadata.chunkIndex() != null ? metadata.chunkIndex() : 0,
                            "chunk"
                    );
                })
                .toList();
    }
    
    @Override
    public String getName() {
        return STAGE_NAME;
    }
}
