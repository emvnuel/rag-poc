package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.Chunk;
import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.rerank.RerankedChunk;
import br.edu.ifba.lightrag.rerank.Reranker;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Executes MIX mode queries.
 * Combines knowledge graph traversal with vector retrieval for comprehensive results.
 * Uses graph expansion to find related entities and relationships.
 * Supports optional reranking of retrieved chunks.
 */
public class MixQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    @Nullable
    private final Reranker reranker;
    
    /**
     * Creates a MixQueryExecutor without reranking support.
     */
    public MixQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String systemPrompt
    ) {
        this(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, 
             entityVectorStorage, graphStorage, systemPrompt, null);
    }
    
    /**
     * Creates a MixQueryExecutor with optional reranking support.
     *
     * @param llmFunction the LLM function for generating answers
     * @param embeddingFunction the embedding function for query embedding
     * @param chunkStorage the KV storage for chunks
     * @param chunkVectorStorage the vector storage for chunk embeddings
     * @param entityVectorStorage the vector storage for entity embeddings
     * @param graphStorage the graph storage for entity/relation data
     * @param systemPrompt the system prompt for the LLM
     * @param reranker optional reranker for improving chunk relevance (can be null)
     */
    public MixQueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull String systemPrompt,
        @Nullable Reranker reranker
    ) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        this.systemPrompt = systemPrompt;
        this.reranker = reranker;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing MIX query");
        
        // Step 1: Embed the query
        return embeddingFunction.embedSingle(query)
            .thenCompose(queryEmbedding -> {
                // Step 2: Search for similar entities (initial seeds) with project filter
                int topK = param.getTopK();
                VectorStorage.VectorFilter entityFilter = new VectorStorage.VectorFilter(
                    "entity", 
                    null, 
                    param.getProjectId()
                );
                CompletableFuture<List<VectorStorage.VectorSearchResult>> entitySearchFuture = 
                    entityVectorStorage.query(queryEmbedding, topK, entityFilter);
                
                // Step 3: Search for relevant chunks in parallel with project filter
                // Request more chunks if reranking is enabled (to have a good pool to rerank from)
                int chunkSearchCount = param.isEnableRerank() && reranker != null 
                    ? param.getChunkTopK() * 2 
                    : param.getChunkTopK();
                VectorStorage.VectorFilter chunkFilter = new VectorStorage.VectorFilter(
                    "chunk", 
                    null, 
                    param.getProjectId()
                );
                CompletableFuture<List<VectorStorage.VectorSearchResult>> chunkSearchFuture = 
                    chunkVectorStorage.query(queryEmbedding, chunkSearchCount, chunkFilter);
                
                return CompletableFuture.allOf(entitySearchFuture, chunkSearchFuture)
                    .thenCompose(v -> {
                        List<VectorStorage.VectorSearchResult> entityResults = entitySearchFuture.join();
                        List<VectorStorage.VectorSearchResult> chunkResults = chunkSearchFuture.join();
                        
                        // Apply reranking if enabled and reranker is available
                        List<VectorStorage.VectorSearchResult> finalChunkResults = 
                            applyReranking(query, chunkResults, param);
                        
                        // Extract entity names from content field (not vector ID)
                        List<String> seedEntityIds = entityResults.stream()
                            .map(result -> result.metadata().content())
                            .toList();
                        
                        if (seedEntityIds.isEmpty()) {
                            // Only chunks available
                            List<LightRAGQueryResult.SourceChunk> chunkSources = convertToSourceChunks(finalChunkResults);
                            return CompletableFuture.completedFuture(
                                new ResultWithSources(chunkSources, Collections.emptyList())
                            );
                        }
                        
                        // Step 4: Expand graph to find related entities (1-hop neighborhood)
                        return expandGraph(param.getProjectId(), seedEntityIds, 1)
                            .thenCompose(expandedEntityIds -> {
                                // Step 5: Get all entities and relations
                                return graphStorage.getEntities(param.getProjectId(), expandedEntityIds)
                                    .thenCompose(entities -> 
                                        getAllRelations(param.getProjectId(), expandedEntityIds)
                                            .thenApply(relations -> {
                                                // Convert entity results to source chunks
                                                List<LightRAGQueryResult.SourceChunk> entitySources = new ArrayList<>();
                                                for (VectorStorage.VectorSearchResult result : entityResults) {
                                                    entitySources.add(new LightRAGQueryResult.SourceChunk(
                                                        result.id(),
                                                        result.metadata().content() != null ? result.metadata().content() : "",
                                                        result.score(),
                                                        result.metadata().documentId() != null ? result.metadata().documentId() : result.id(),
                                                        result.id(),
                                                        0,
                                                        "entity"
                                                    ));
                                                }
                                                
                                                // Convert chunk results to source chunks
                                                List<LightRAGQueryResult.SourceChunk> chunkSources = convertToSourceChunks(finalChunkResults);
                                                
                                                return new ResultWithSources(
                                                    chunkSources,
                                                    entitySources
                                                );
                                            })
                                    );
                            });
                    });
            })
            .thenCompose(resultWithSources -> {
                // Combine all sources: entities without citations, chunks with UUID citations
                List<LightRAGQueryResult.SourceChunk> allSources = new ArrayList<>();
                StringBuilder combinedContext = new StringBuilder();
                
                // Add entity sources WITHOUT citations (knowledge graph provides context only)
                if (!resultWithSources.entitySources.isEmpty()) {
                    combinedContext.append("Knowledge Graph Context:\n");
                    for (LightRAGQueryResult.SourceChunk source : resultWithSources.entitySources) {
                        combinedContext.append(source.content()).append("\n\n");
                        allSources.add(source);
                    }
                }
                
                // Add chunk sources WITH UUID citations
                if (!resultWithSources.chunkSources.isEmpty()) {
                    combinedContext.append("Supporting Text Context:\n");
                    for (LightRAGQueryResult.SourceChunk source : resultWithSources.chunkSources) {
                        if (source.documentId() != null) {
                            combinedContext.append(String.format("[%s] %s\n\n", 
                                source.documentId(), source.content()));
                        } else {
                            combinedContext.append(source.content()).append("\n\n");
                        }
                        allSources.add(source);
                    }
                }
                
                String finalContext = combinedContext.toString();
                
                // Step 6: Build prompt and call LLM
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(
                        new LightRAGQueryResult(finalContext, allSources, param.getMode(), allSources.size())
                    );
                }
                
                String prompt = buildPrompt(query, finalContext, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(
                        new LightRAGQueryResult(prompt, allSources, param.getMode(), allSources.size())
                    );
                }
                
                return llmFunction.apply(prompt, systemPrompt)
                    .thenApply(answer -> new LightRAGQueryResult(
                        answer,
                        allSources,
                        param.getMode(),
                        allSources.size()
                    ));
            });
    }
    
    /**
     * Helper class to carry sources through the pipeline
     */
    private static class ResultWithSources {
        final List<LightRAGQueryResult.SourceChunk> chunkSources;
        final List<LightRAGQueryResult.SourceChunk> entitySources;
        
        ResultWithSources(List<LightRAGQueryResult.SourceChunk> chunkSources,
                         List<LightRAGQueryResult.SourceChunk> entitySources) {
            this.chunkSources = chunkSources;
            this.entitySources = entitySources;
        }
    }
    
    /**
     * Expands graph from seed entities using N-hop traversal.
     * 
     * @param projectId The project ID for graph isolation
     * @param seedEntityIds Initial entity IDs
     * @param hops Number of hops to expand
     * @return All entity IDs within N hops
     */
    private CompletableFuture<List<String>> expandGraph(
        @NotNull String projectId,
        @NotNull List<String> seedEntityIds,
        int hops
    ) {
        if (hops <= 0 || seedEntityIds.isEmpty()) {
            return CompletableFuture.completedFuture(seedEntityIds);
        }
        
        Set<String> visited = new HashSet<>(seedEntityIds);
        Set<String> currentLevel = new HashSet<>(seedEntityIds);
        
        // Recursive expansion
        return expandGraphLevel(projectId, currentLevel, visited, hops)
            .thenApply(finalVisited -> new ArrayList<>(finalVisited));
    }
    
    /**
     * Recursively expands graph level by level.
     */
    private CompletableFuture<Set<String>> expandGraphLevel(
        @NotNull String projectId,
        @NotNull Set<String> currentLevel,
        @NotNull Set<String> visited,
        int remainingHops
    ) {
        if (remainingHops <= 0 || currentLevel.isEmpty()) {
            return CompletableFuture.completedFuture(visited);
        }
        
        // Get all relations for current level entities
        List<CompletableFuture<List<Relation>>> futures = currentLevel.stream()
            .map(entityId -> graphStorage.getRelationsForEntity(projectId, entityId))
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                // Collect all neighbor entity IDs
                Set<String> nextLevel = new HashSet<>();
                for (CompletableFuture<List<Relation>> future : futures) {
                    for (Relation relation : future.join()) {
                        String srcId = relation.getSrcId();
                        String tgtId = relation.getTgtId();
                        
                        if (!visited.contains(srcId)) {
                            nextLevel.add(srcId);
                            visited.add(srcId);
                        }
                        if (!visited.contains(tgtId)) {
                            nextLevel.add(tgtId);
                            visited.add(tgtId);
                        }
                    }
                }
                
                // Recursively expand next level
                return expandGraphLevel(projectId, nextLevel, visited, remainingHops - 1);
            });
    }
    
    /**
     * Gets all relations between a set of entities.
     */
    private CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId, @NotNull List<String> entityIds) {
        if (entityIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        List<CompletableFuture<List<Relation>>> futures = entityIds.stream()
            .map(entityId -> graphStorage.getRelationsForEntity(projectId, entityId))
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Set<String> relationIds = new HashSet<>();
                List<Relation> allRelations = new ArrayList<>();
                
                for (CompletableFuture<List<Relation>> future : futures) {
                    for (Relation relation : future.join()) {
                        // Use a unique key to deduplicate relations
                        String relationKey = relation.getSrcId() + "->" + relation.getTgtId();
                        if (relationIds.add(relationKey)) {
                            allRelations.add(relation);
                        }
                    }
                }
                
                return allRelations;
            });
    }
    
    /**
     * Formats entities and relations into a readable context string.
     */
    private String formatGraphContextWithEntities(
        @NotNull List<Entity> entities,
        @NotNull List<Relation> relations
    ) {
        StringBuilder context = new StringBuilder();
        
        if (!entities.isEmpty()) {
            for (Entity entity : entities) {
                context.append(entity.getEntityName())
                    .append(" (")
                    .append(entity.getEntityType())
                    .append(")");
                
                String description = entity.getDescription();
                if (description != null && !description.isEmpty()) {
                    context.append(": ").append(description);
                }
                
                context.append("\n");
            }
        }
        
        if (!relations.isEmpty()) {
            context.append("\nRelationships:\n");
            for (Relation relation : relations) {
                context.append(relation.getSrcId())
                    .append(" -> ")
                    .append(relation.getTgtId());
                
                String description = relation.getDescription();
                if (description != null && !description.isEmpty()) {
                    context.append(": ").append(description);
                }
                
                context.append(" [weight: ")
                    .append(String.format("%.2f", relation.getWeight()))
                    .append("]\n");
            }
        }
        
        return context.toString();
    }
    
    /**
     * Applies reranking to chunk search results if enabled.
     * 
     * <p>When reranking is enabled and a reranker is available:
     * <ol>
     *   <li>Converts vector search results to Chunk objects</li>
     *   <li>Reranks chunks using the configured reranker</li>
     *   <li>Converts back to VectorSearchResult format with updated scores</li>
     *   <li>Returns only topK results</li>
     * </ol>
     * 
     * <p>Falls back to original results if reranking is disabled or reranker is unavailable.
     *
     * @param query the query string for reranking
     * @param chunkResults original vector search results
     * @param param query parameters including rerank flag and topK
     * @return reranked results or original if reranking not applicable
     */
    private List<VectorStorage.VectorSearchResult> applyReranking(
        @NotNull String query,
        @NotNull List<VectorStorage.VectorSearchResult> chunkResults,
        @NotNull QueryParam param
    ) {
        // Skip if reranking disabled, no reranker, or no chunks
        if (!param.isEnableRerank() || reranker == null || chunkResults.isEmpty()) {
            logger.debug("Reranking skipped: enabled={}, reranker={}, chunks={}",
                param.isEnableRerank(), reranker != null ? reranker.getProviderName() : "null", 
                Integer.valueOf(chunkResults.size()));
            // Limit to topK if we fetched extra for reranking
            return chunkResults.size() > param.getChunkTopK() 
                ? chunkResults.subList(0, param.getChunkTopK()) 
                : chunkResults;
        }
        
        // Check if reranker is available
        if (!reranker.isAvailable()) {
            logger.warn("Reranker {} not available, using original order", reranker.getProviderName());
            return chunkResults.size() > param.getChunkTopK() 
                ? chunkResults.subList(0, param.getChunkTopK()) 
                : chunkResults;
        }
        
        logger.debug("Applying reranking with {} to {} chunks", 
            reranker.getProviderName(), Integer.valueOf(chunkResults.size()));
        
        // Convert to Chunk objects
        List<Chunk> chunks = new ArrayList<>();
        Map<String, VectorStorage.VectorSearchResult> resultByChunkId = new HashMap<>();
        
        for (VectorStorage.VectorSearchResult result : chunkResults) {
            String content = result.metadata().content();
            if (content != null && !content.isEmpty()) {
                Chunk chunk = new Chunk(
                    content,
                    result.metadata().documentId(),
                    result.id(),
                    0  // tokens not needed for reranking
                );
                chunks.add(chunk);
                resultByChunkId.put(result.id(), result);
            }
        }
        
        // Rerank
        List<RerankedChunk> rerankedChunks = reranker.rerank(query, chunks, param.getChunkTopK());
        
        // Convert back to VectorSearchResult with updated scores
        List<VectorStorage.VectorSearchResult> rerankedResults = new ArrayList<>();
        for (RerankedChunk reranked : rerankedChunks) {
            VectorStorage.VectorSearchResult original = resultByChunkId.get(reranked.chunk().getChunkId());
            if (original != null) {
                // Create new result with reranked score
                rerankedResults.add(new VectorStorage.VectorSearchResult(
                    original.id(),
                    reranked.relevanceScore(),  // Updated score from reranker
                    original.metadata()
                ));
            }
        }
        
        logger.debug("Reranking complete: {} -> {} chunks", 
            Integer.valueOf(chunks.size()), Integer.valueOf(rerankedResults.size()));
        
        return rerankedResults;
    }
}
