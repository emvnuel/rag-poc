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
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing HYBRID query");
        
        // Execute both local and global retrieval in parallel
        CompletableFuture<LightRAGQueryResult> localResult = localExecutor.execute(query, param);
        CompletableFuture<LightRAGQueryResult> globalResult = globalExecutor.execute(query, param);
        
        return CompletableFuture.allOf(localResult, globalResult)
            .thenCompose(v -> {
                LightRAGQueryResult localRes = localResult.join();
                LightRAGQueryResult globalRes = globalResult.join();
                
                // Combine source chunks from both executors
                List<LightRAGQueryResult.SourceChunk> combinedSources = new ArrayList<>();
                combinedSources.addAll(localRes.sourceChunks());
                combinedSources.addAll(globalRes.sourceChunks());
                
                // Build combined context with UUID citations for LOCAL chunks, no citations for GLOBAL entities
                StringBuilder combinedContext = new StringBuilder();
                
                if (!localRes.sourceChunks().isEmpty()) {
                    combinedContext.append("Local Context (Text Chunks):\n");
                    for (LightRAGQueryResult.SourceChunk chunk : localRes.sourceChunks()) {
                        // LOCAL chunks: Use UUID citations if documentId is available
                        if (chunk.documentId() != null) {
                            combinedContext.append(String.format("[%s] %s\n\n", chunk.documentId(), chunk.content()));
                        } else {
                            combinedContext.append(chunk.content()).append("\n\n");
                        }
                    }
                }
                
                if (!globalRes.sourceChunks().isEmpty()) {
                    combinedContext.append("Global Context (Knowledge Graph):\n");
                    for (LightRAGQueryResult.SourceChunk chunk : globalRes.sourceChunks()) {
                        // GLOBAL entities: No citations, just context
                        combinedContext.append(chunk.content()).append("\n\n");
                    }
                }
                
                String finalContext = combinedContext.toString();
                
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(
                        new LightRAGQueryResult(finalContext, combinedSources, param.getMode(), combinedSources.size())
                    );
                }
                
                String prompt = buildPrompt(query, finalContext, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(
                        new LightRAGQueryResult(prompt, combinedSources, param.getMode(), combinedSources.size())
                    );
                }
                
                // Call LLM with combined context
                return llmFunction.apply(prompt, systemPrompt)
                    .thenApply(answer -> new LightRAGQueryResult(
                        answer,
                        combinedSources,
                        param.getMode(),
                        combinedSources.size()
                    ));
            });
    }
}
