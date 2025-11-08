package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Executes MIX mode queries.
 * Combines knowledge graph traversal with vector retrieval for comprehensive results.
 * Uses graph expansion to find related entities and relationships.
 */
public class MixQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    
    public MixQueryExecutor(
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
        logger.info("Executing MIX query");
        
        // Step 1: Embed the query
        return embeddingFunction.embedSingle(query)
            .thenCompose(queryEmbedding -> {
                // Step 2: Search for similar entities (initial seeds)
                int topK = param.getTopK();
                return entityVectorStorage.query(queryEmbedding, topK, null)
                    .thenCompose(results -> {
                        List<String> seedEntityIds = results.stream()
                            .map(VectorStorage.VectorSearchResult::id)
                            .toList();
                        
                        if (seedEntityIds.isEmpty()) {
                            return CompletableFuture.completedFuture("");
                        }
                        
                        // Step 3: Expand graph to find related entities (1-hop neighborhood)
                        return expandGraph(seedEntityIds, 1)
                            .thenCompose(expandedEntityIds -> {
                                // Step 4: Get all entities and relations
                                return graphStorage.getEntities(expandedEntityIds)
                                    .thenCompose(entities -> 
                                        getAllRelations(expandedEntityIds)
                                            .thenApply(relations -> formatGraphContext(entities, relations))
                                    );
                            });
                    })
                    .thenCompose(graphContext -> {
                        // Step 5: Also get relevant chunks for detailed context
                        return chunkVectorStorage.query(queryEmbedding, param.getChunkTopK(), null)
                            .thenCompose(chunkResults -> {
                                List<CompletableFuture<String>> chunkFutures = new ArrayList<>();
                                for (VectorStorage.VectorSearchResult result : chunkResults) {
                                    String content = result.metadata().content();
                                    if (content != null && !content.isEmpty()) {
                                        chunkFutures.add(CompletableFuture.completedFuture(content));
                                    } else {
                                        chunkFutures.add(chunkStorage.get(result.id()));
                                    }
                                }
                                
                                if (chunkFutures.isEmpty()) {
                                    return CompletableFuture.completedFuture(graphContext);
                                }
                                
                                return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                                    .thenApply(v -> {
                                        List<String> chunks = chunkFutures.stream()
                                            .map(CompletableFuture::join)
                                            .filter(c -> c != null && !c.isEmpty())
                                            .toList();
                                        
                                        // Combine graph and chunk contexts
                                        StringBuilder combinedContext = new StringBuilder();
                                        
                                        if (!graphContext.isEmpty()) {
                                            combinedContext.append("Knowledge Graph Context:\n");
                                            combinedContext.append(graphContext);
                                            combinedContext.append("\n\n");
                                        }
                                        
                                        if (!chunks.isEmpty()) {
                                            combinedContext.append("Supporting Text Context:\n");
                                            combinedContext.append(formatChunkContext(chunks));
                                        }
                                        
                                        return combinedContext.toString();
                                    });
                            });
                    });
            })
            .thenCompose(context -> {
                // Step 6: Build prompt and call LLM
                if (param.isOnlyNeedContext()) {
                    return CompletableFuture.completedFuture(context);
                }
                
                String prompt = buildPrompt(query, context, param);
                
                if (param.isOnlyNeedPrompt()) {
                    return CompletableFuture.completedFuture(prompt);
                }
                
                return llmFunction.apply(prompt, systemPrompt);
            });
    }
    
    /**
     * Expands the graph by traversing N hops from seed entities.
     *
     * @param seedEntityIds Initial entity IDs
     * @param hops Number of hops to expand
     * @return All entity IDs within N hops
     */
    private CompletableFuture<List<String>> expandGraph(
        @NotNull List<String> seedEntityIds,
        int hops
    ) {
        if (hops <= 0 || seedEntityIds.isEmpty()) {
            return CompletableFuture.completedFuture(seedEntityIds);
        }
        
        Set<String> visited = new HashSet<>(seedEntityIds);
        Set<String> currentLevel = new HashSet<>(seedEntityIds);
        
        // Recursive expansion
        return expandGraphLevel(currentLevel, visited, hops)
            .thenApply(finalVisited -> new ArrayList<>(finalVisited));
    }
    
    /**
     * Recursively expands graph level by level.
     */
    private CompletableFuture<Set<String>> expandGraphLevel(
        @NotNull Set<String> currentLevel,
        @NotNull Set<String> visited,
        int remainingHops
    ) {
        if (remainingHops <= 0 || currentLevel.isEmpty()) {
            return CompletableFuture.completedFuture(visited);
        }
        
        // Get all relations for current level entities
        List<CompletableFuture<List<Relation>>> futures = currentLevel.stream()
            .map(graphStorage::getRelationsForEntity)
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
                return expandGraphLevel(nextLevel, visited, remainingHops - 1);
            });
    }
    
    /**
     * Gets all relations between a set of entities.
     */
    private CompletableFuture<List<Relation>> getAllRelations(@NotNull List<String> entityIds) {
        if (entityIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        List<CompletableFuture<List<Relation>>> futures = entityIds.stream()
            .map(graphStorage::getRelationsForEntity)
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
    private String formatGraphContext(
        @NotNull List<Entity> entities,
        @NotNull List<Relation> relations
    ) {
        StringBuilder context = new StringBuilder();
        
        if (!entities.isEmpty()) {
            context.append("Entities:\n");
            for (Entity entity : entities) {
                context.append("- ")
                    .append(entity.getEntityName())
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
                context.append("- ")
                    .append(relation.getSrcId())
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
}
