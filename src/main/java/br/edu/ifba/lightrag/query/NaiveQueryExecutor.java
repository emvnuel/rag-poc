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
    public CompletableFuture<String> execute(
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
                        return chunkVectorStorage.query(queryEmbedding, topK, null);
                    });
            })
            .thenCompose(results -> {
                // Retrieve chunk contents
                List<CompletableFuture<String>> chunkFutures = new ArrayList<>();
                for (VectorStorage.VectorSearchResult result : results) {
                    String content = result.metadata().content();
                    if (content != null && !content.isEmpty()) {
                        chunkFutures.add(CompletableFuture.completedFuture(content));
                    } else {
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
                String context = formatChunkContext(chunks);
                
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(context);
                }
                
                String prompt = buildPrompt(query, context, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(prompt);
                }
                
                // Call LLM with system prompt
                return llmFunction.apply(prompt, systemPrompt);
            });
    }
}
