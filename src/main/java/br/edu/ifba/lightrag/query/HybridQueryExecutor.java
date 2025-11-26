package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.utils.TokenUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes HYBRID mode queries.
 * 
 * <p>HYBRID mode combines local chunk-based retrieval with global entity-based retrieval,
 * using round-robin interleaving to ensure diversity in the final context.</p>
 * 
 * <h2>Features:</h2>
 * <ul>
 *   <li>Keyword extraction: Uses both high-level (for GLOBAL) and low-level (for LOCAL) keywords</li>
 *   <li>Round-robin merging: Interleaves results from LOCAL and GLOBAL for diversity</li>
 *   <li>Token budget: Enforces max context tokens from configuration</li>
 * </ul>
 */
public class HybridQueryExecutor extends QueryExecutor {
    
    private final LocalQueryExecutor localExecutor;
    private final GlobalQueryExecutor globalExecutor;
    private final String systemPrompt;
    private final ContextMerger contextMerger;
    private final LightRAGExtractionConfig config;
    
    /** Default max tokens if config is not provided */
    private static final int DEFAULT_MAX_TOKENS = 4000;
    
    /**
     * Creates a HybridQueryExecutor without keyword extraction (backward compatible).
     */
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
        this(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage,
             entityVectorStorage, graphStorage, localSystemPrompt, globalSystemPrompt, 
             systemPrompt, null, null);
    }
    
    /**
     * Creates a HybridQueryExecutor with optional keyword extraction and config.
     * 
     * @param llmFunction LLM function for generating responses
     * @param embeddingFunction Embedding function for vector search
     * @param chunkStorage KV storage for chunks
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for relationships
     * @param localSystemPrompt System prompt for LOCAL queries
     * @param globalSystemPrompt System prompt for GLOBAL queries
     * @param systemPrompt System prompt for final HYBRID response
     * @param keywordExtractor Optional keyword extractor (null to disable)
     * @param config Optional configuration for token budgets (null for defaults)
     */
    public HybridQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String localSystemPrompt,
        @NotNull String globalSystemPrompt,
        @NotNull String systemPrompt,
        @Nullable KeywordExtractor keywordExtractor,
        @Nullable LightRAGExtractionConfig config
    ) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        
        // Create child executors with keyword extractor support
        this.localExecutor = new LocalQueryExecutor(
            llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, 
            entityVectorStorage, graphStorage, localSystemPrompt, keywordExtractor
        );
        this.globalExecutor = new GlobalQueryExecutor(
            llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, 
            entityVectorStorage, graphStorage, globalSystemPrompt, keywordExtractor
        );
        this.systemPrompt = systemPrompt;
        this.contextMerger = new ContextMerger();
        this.config = config;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing HYBRID query");
        
        // Execute both local and global retrieval in parallel
        // Use context-only mode to get raw results for merging
        QueryParam contextOnlyParam = param.toBuilder().onlyNeedContext(true).build();
        
        CompletableFuture<LightRAGQueryResult> localResult = localExecutor.execute(query, contextOnlyParam);
        CompletableFuture<LightRAGQueryResult> globalResult = globalExecutor.execute(query, contextOnlyParam);
        
        return CompletableFuture.allOf(localResult, globalResult)
            .thenCompose(v -> {
                LightRAGQueryResult localRes = localResult.join();
                LightRAGQueryResult globalRes = globalResult.join();
                
                // Convert source chunks to ContextItems for round-robin merging
                List<ContextItem> localItems = convertToContextItems(localRes.sourceChunks(), true);
                List<ContextItem> globalItems = convertToContextItems(globalRes.sourceChunks(), false);
                
                // Get max tokens from config or use default
                int maxTokens = config != null 
                    ? config.query().context().maxTokens() 
                    : DEFAULT_MAX_TOKENS;
                
                // Use round-robin merging for diversity
                MergeResult mergeResult = contextMerger.mergeWithMetadata(
                    List.of(localItems, globalItems), 
                    maxTokens
                );
                
                logger.debug("Merged {} items ({} truncated) using {} tokens", 
                    mergeResult.itemsIncluded(), 
                    mergeResult.itemsTruncated(),
                    mergeResult.totalTokens());
                
                // Combine source chunks from both executors (preserve order for metadata)
                List<LightRAGQueryResult.SourceChunk> combinedSources = new ArrayList<>();
                combinedSources.addAll(localRes.sourceChunks());
                combinedSources.addAll(globalRes.sourceChunks());
                
                // Build final context with section headers
                String finalContext = buildContextWithHeaders(mergeResult, localItems, globalItems);
                
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
    
    /**
     * Converts source chunks to ContextItems for merging.
     * 
     * @param chunks source chunks from query result
     * @param isLocal true if chunks are from LOCAL mode (include citations)
     * @return list of ContextItems
     */
    private List<ContextItem> convertToContextItems(
        @NotNull List<LightRAGQueryResult.SourceChunk> chunks, 
        boolean isLocal
    ) {
        List<ContextItem> items = new ArrayList<>();
        
        for (LightRAGQueryResult.SourceChunk chunk : chunks) {
            String content;
            if (isLocal && chunk.documentId() != null) {
                // LOCAL chunks: Include UUID citation
                content = String.format("[%s] %s", chunk.documentId(), chunk.content());
            } else {
                // GLOBAL entities: No citation
                content = chunk.content();
            }
            
            String type = isLocal ? "chunk" : "entity";
            int tokens = TokenUtil.estimateTokens(content);
            
            items.add(new ContextItem(
                content,
                type,
                chunk.chunkId(),
                null, // filePath not available in SourceChunk
                tokens
            ));
        }
        
        return items;
    }
    
    /**
     * Builds context string with section headers from merged items.
     * 
     * @param mergeResult the result from round-robin merging
     * @param localItems original local items (for header detection)
     * @param globalItems original global items (for header detection)
     * @return formatted context string with headers
     */
    private String buildContextWithHeaders(
        @NotNull MergeResult mergeResult,
        @NotNull List<ContextItem> localItems,
        @NotNull List<ContextItem> globalItems
    ) {
        // If merge result is empty, return empty string
        if (mergeResult.includedItems().isEmpty()) {
            return "";
        }
        
        // Check which types are included
        boolean hasLocal = mergeResult.includedItems().stream()
            .anyMatch(item -> "chunk".equals(item.type()));
        boolean hasGlobal = mergeResult.includedItems().stream()
            .anyMatch(item -> "entity".equals(item.type()));
        
        StringBuilder context = new StringBuilder();
        
        // Add local section if present
        if (hasLocal) {
            context.append("Local Context (Text Chunks):\n");
            for (ContextItem item : mergeResult.includedItems()) {
                if ("chunk".equals(item.type())) {
                    context.append(item.content()).append("\n\n");
                }
            }
        }
        
        // Add global section if present
        if (hasGlobal) {
            context.append("Global Context (Knowledge Graph):\n");
            for (ContextItem item : mergeResult.includedItems()) {
                if ("entity".equals(item.type())) {
                    context.append(item.content()).append("\n\n");
                }
            }
        }
        
        return context.toString();
    }
}
