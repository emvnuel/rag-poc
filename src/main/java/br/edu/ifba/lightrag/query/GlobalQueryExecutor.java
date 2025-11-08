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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes GLOBAL mode queries.
 * Utilizes global knowledge from entities and their relationships.
 */
public class GlobalQueryExecutor extends QueryExecutor {
    
    private final String systemPrompt;
    
    public GlobalQueryExecutor(
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
        logger.info("Executing GLOBAL query");
        
        // Step 1: Embed the query
        return embeddingFunction.embedSingle(query)
            .thenCompose(queryEmbedding -> {
                // Step 2: Search for similar entities
                int topK = param.getTopK();
                return entityVectorStorage.query(queryEmbedding, topK, null);
            })
            .thenCompose(results -> {
                // Step 3: Extract entity IDs from results
                List<String> entityIds = results.stream()
                    .map(VectorStorage.VectorSearchResult::id)
                    .toList();
                
                if (entityIds.isEmpty()) {
                    return CompletableFuture.completedFuture("");
                }
                
                // Step 4: Get entities and their relationships from graph
                return graphStorage.getEntities(entityIds).thenCompose((List<Entity> entities) -> {
                    // Get relations for each entity
                    List<CompletableFuture<List<Relation>>> relationFutures = new ArrayList<>();
                    for (String entityId : entityIds) {
                        relationFutures.add(graphStorage.getRelationsForEntity(entityId));
                    }
                    
                    return CompletableFuture.allOf(relationFutures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> {
                            // Combine all relations
                            List<Relation> allRelations = relationFutures.stream()
                                .flatMap(f -> f.join().stream())
                                .distinct() // Remove duplicates
                                .toList();
                            
                            // Format entities and relations as context
                            StringBuilder context = new StringBuilder();
                            
                            context.append("Entities:\n");
                            for (Entity entity : entities) {
                                context.append("- ")
                                    .append(entity.getEntityName())
                                    .append(" (")
                                    .append(entity.getEntityType())
                                    .append("): ")
                                    .append(entity.getDescription())
                                    .append("\n");
                            }
                            
                            context.append("\nRelationships:\n");
                            for (Relation relation : allRelations) {
                                context.append("- ")
                                    .append(relation.getSrcId())
                                    .append(" -> ")
                                    .append(relation.getTgtId())
                                    .append(": ")
                                    .append(relation.getDescription())
                                    .append("\n");
                            }
                            
                            return context.toString();
                        });
                });
            })
            .thenCompose(context -> {
                // Step 5: Build prompt and call LLM
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
}
