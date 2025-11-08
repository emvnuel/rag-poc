package br.edu.ifba.lightrag.query;

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
    public CompletableFuture<String> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing LOCAL query");
        
        // Step 1: Embed the query
        return embeddingFunction.embedSingle(query)
            .thenCompose(queryEmbedding -> {
                // Step 2: Search for similar chunks
                int topK = param.getChunkTopK();
                return chunkVectorStorage.query(queryEmbedding, topK, null);
            })
            .thenCompose(results -> {
                // Step 3: Retrieve chunk contents
                List<CompletableFuture<String>> chunkFutures = new ArrayList<>();
                for (VectorStorage.VectorSearchResult result : results) {
                    // Try to get content from metadata first (faster)
                    String content = result.metadata().content();
                    if (content != null && !content.isEmpty()) {
                        chunkFutures.add(CompletableFuture.completedFuture(content));
                    } else {
                        // Fallback to storage lookup
                        chunkFutures.add(chunkStorage.get(result.id()));
                    }
                }
                
                return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> chunkFutures.stream()
                        .map(CompletableFuture::join)
                        .filter(c -> c != null && !c.isEmpty())
                        .toList());
            })
            .thenCompose(chunks -> {
                // Step 4: Build context and prompt
                String context = formatChunkContext(chunks);
                
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(context);
                }
                
                String prompt = buildPrompt(query, context, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(prompt);
                }
                
                // Step 5: Call LLM with context
                return llmFunction.apply(prompt, systemPrompt);
            });
    }
}
