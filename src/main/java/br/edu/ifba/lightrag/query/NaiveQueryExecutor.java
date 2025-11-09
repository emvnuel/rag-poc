package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes NAIVE mode queries.
 * Performs basic keyword search without advanced techniques.
 */
public class NaiveQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    
    public NaiveQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String systemPrompt
    ) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        this.systemPrompt = systemPrompt;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing NAIVE query");
        
        // Naive approach: just get all chunks and do simple text matching
        // In production, this could use full-text search or basic keyword matching
        return chunkStorage.keys()
            .thenCompose(allKeys -> {
                // For simplicity, just use vector search like LOCAL mode
                // but with a different system prompt
                return embeddingFunction.embedSingle(query)
                    .thenCompose(queryEmbedding -> {
                        int topK = Math.min(param.getChunkTopK(), 5); // Use fewer results for naive
                        VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
                            "chunk", 
                            null, 
                            param.getProjectId()
                        );
                        return chunkVectorStorage.query(queryEmbedding, topK, filter);
                    });
            })
            .thenCompose(results -> {
                // Convert to source chunks
                List<LightRAGQueryResult.SourceChunk> sourceChunks = convertToSourceChunks(results);
                
                // Build context with citations
                String context = formatChunkContextWithCitations(results);
                
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(
                        new LightRAGQueryResult(context, sourceChunks, param.getMode(), sourceChunks.size())
                    );
                }
                
                String prompt = buildPrompt(query, context, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(
                        new LightRAGQueryResult(prompt, sourceChunks, param.getMode(), sourceChunks.size())
                    );
                }
                
                // Call LLM with system prompt
                return llmFunction.apply(prompt, systemPrompt)
                    .thenApply(answer -> new LightRAGQueryResult(
                        answer,
                        sourceChunks,
                        param.getMode(),
                        sourceChunks.size()
                    ));
            });
    }
}
