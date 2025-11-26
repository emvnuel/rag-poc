package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.storage.VectorStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chunk selector using vector similarity search.
 * 
 * <p>This is the default chunk selection strategy that uses embedding-based
 * similarity search to find chunks most relevant to the query.</p>
 * 
 * <h2>Strategy:</h2>
 * <ol>
 *   <li>Takes the query embedding</li>
 *   <li>Searches vector storage for similar chunk embeddings</li>
 *   <li>Returns chunks ordered by cosine similarity score</li>
 * </ol>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class VectorChunkSelector implements ChunkSelector {
    
    private static final Logger logger = LoggerFactory.getLogger(VectorChunkSelector.class);
    private static final String STRATEGY_NAME = "vector";
    
    private final VectorStorage chunkVectorStorage;
    
    /**
     * Default constructor for CDI proxy.
     */
    public VectorChunkSelector() {
        this.chunkVectorStorage = null;
    }
    
    /**
     * Creates a VectorChunkSelector with the given storage.
     * 
     * @param chunkVectorStorage The vector storage for chunk embeddings
     */
    @Inject
    public VectorChunkSelector(VectorStorage chunkVectorStorage) {
        this.chunkVectorStorage = chunkVectorStorage;
    }
    
    @Override
    public CompletableFuture<List<ScoredChunk>> selectChunks(
            @NotNull Object queryEmbedding,
            @NotNull String projectId,
            int topK,
            SelectionContext context) {
        
        logger.debug("Selecting chunks using vector similarity, topK={}, projectId={}", topK, projectId);
        
        if (chunkVectorStorage == null) {
            logger.warn("ChunkVectorStorage not initialized, returning empty list");
            return CompletableFuture.completedFuture(List.of());
        }
        
        VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
            "chunk",
            null,
            projectId
        );
        
        return chunkVectorStorage.query(queryEmbedding, topK, filter)
            .thenApply(results -> {
                logger.debug("Vector search returned {} results", results.size());
                
                return results.stream()
                    .map(ScoredChunk::fromVectorResult)
                    .toList();
            });
    }
    
    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }
}
