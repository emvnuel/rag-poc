package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Executes HYBRID mode queries.
 * Combines local chunk-based retrieval with global entity-based retrieval.
 */
public class HybridQueryExecutor extends QueryExecutor {
    
    private final LocalQueryExecutor localExecutor;
    private final GlobalQueryExecutor globalExecutor;
    private final String systemPrompt;
    
    public HybridQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String localSystemPrompt,
        @NotNull String globalSystemPrompt,
        @NotNull String systemPrompt
    ) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        this.localExecutor = new LocalQueryExecutor(
            llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage, localSystemPrompt
        );
        this.globalExecutor = new GlobalQueryExecutor(
            llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage, globalSystemPrompt
        );
        this.systemPrompt = systemPrompt;
    }
    
    @Override
    public CompletableFuture<String> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing HYBRID query");
        
        // Create modified params to get context only
        QueryParam contextParam = QueryParam.builder()
            .mode(QueryParam.Mode.HYBRID)
            .onlyNeedContext(true)
            .topK(param.getTopK())
            .chunkTopK(param.getChunkTopK())
            .build();
        
        // Execute both local and global retrieval in parallel
        CompletableFuture<String> localContext = localExecutor.execute(query, contextParam);
        CompletableFuture<String> globalContext = globalExecutor.execute(query, contextParam);
        
        return CompletableFuture.allOf(localContext, globalContext)
            .thenCompose(v -> {
                String localCtx = localContext.join();
                String globalCtx = globalContext.join();
                
                // Combine contexts
                StringBuilder combinedContext = new StringBuilder();
                
                if (!localCtx.isEmpty()) {
                    combinedContext.append("Local Context (Text Chunks):\n");
                    combinedContext.append(localCtx);
                    combinedContext.append("\n");
                }
                
                if (!globalCtx.isEmpty()) {
                    combinedContext.append("Global Context (Knowledge Graph):\n");
                    combinedContext.append(globalCtx);
                }
                
                String finalContext = combinedContext.toString();
                
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(finalContext);
                }
                
                String prompt = buildPrompt(query, finalContext, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(prompt);
                }
                
                // Call LLM with combined context
                return llmFunction.apply(prompt, systemPrompt);
            });
    }
}
