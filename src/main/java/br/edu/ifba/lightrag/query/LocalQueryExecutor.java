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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes LOCAL mode queries.
 * Focuses on context-dependent information using vector similarity search on chunks.
 */
public class LocalQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    
    public LocalQueryExecutor(
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
        logger.info("Executing LOCAL query");
        
        // Step 1: Embed the query
        return embeddingFunction.embedSingle(query)
            .thenCompose(queryEmbedding -> {
                // Step 2: Search for similar chunks with project filter
                int topK = param.getChunkTopK();
                VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
                    "chunk", 
                    null, 
                    param.getProjectId()
                );
                return chunkVectorStorage.query(queryEmbedding, topK, filter);
            })
            .thenCompose(results -> {
                // Step 3: Build context and prompt with citations
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
                
                // Step 4: Call LLM with context
                return llmFunction.apply(prompt, systemPrompt)
                    .thenApply(answer -> new LightRAGQueryResult(
                        answer,
                        convertToSourceChunks(results),
                        QueryParam.Mode.LOCAL,
                        results.size()
                    ));
            });
    }
}
