package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.Relation;
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
    public CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        logger.info("Executing GLOBAL query");
        
        // Step 1: Embed the query
        return embeddingFunction.embedSingle(query)
            .thenCompose(queryEmbedding -> {
                // Step 2: Search for similar entities with project filter
                int topK = param.getTopK();
                VectorStorage.VectorFilter filter = new VectorStorage.VectorFilter(
                    "entity", 
                    null, 
                    param.getProjectId()
                );
                return entityVectorStorage.query(queryEmbedding, topK, filter);
            })
            .thenCompose(entityResults -> {
                // Step 3: Extract entity names from results (content field contains entity name)
                List<String> entityIds = entityResults.stream()
                    .map(result -> result.metadata().content())
                    .toList();
                
                if (entityIds.isEmpty()) {
                    return CompletableFuture.completedFuture(new LightRAGQueryResult(
                        "",
                        Collections.emptyList(),
                        QueryParam.Mode.GLOBAL,
                        0
                    ));
                }
                
                // Step 4: Get entities and their relationships from graph
                return graphStorage.getEntities(entityIds).thenCompose((List<Entity> entities) -> {
                    // Get relations for each entity
                    List<CompletableFuture<List<Relation>>> relationFutures = new ArrayList<>();
                    for (String entityId : entityIds) {
                        relationFutures.add(graphStorage.getRelationsForEntity(entityId));
                    }
                    
                    return CompletableFuture.allOf(relationFutures.toArray(new CompletableFuture[0]))
                        .thenCompose(v -> {
                            // Combine all relations
                            List<Relation> allRelations = relationFutures.stream()
                                .flatMap(f -> f.join().stream())
                                .distinct() // Remove duplicates
                                .toList();
                            
                            // Format entities and relations as context WITHOUT citations
                            // Entities don't have document UUIDs, so they provide background context only
                            StringBuilder context = new StringBuilder();
                            
                            context.append("Entities:\n");
                            for (Entity entity : entities) {
                                context.append(entity.getEntityName())
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
                            
                            // Convert entity results to source chunks
                            List<SourceChunk> sourceChunks = new ArrayList<>();
                            for (int i = 0; i < entityResults.size() && i < entities.size(); i++) {
                                VectorStorage.VectorSearchResult result = entityResults.get(i);
                                Entity entity = entities.get(i);
                                
                                sourceChunks.add(new SourceChunk(
                                    result.id(),                          // chunkId (entity ID)
                                    entity.getDescription(),              // content
                                    result.score(),                       // relevanceScore
                                    null,                                 // documentId (not applicable for entities)
                                    entity.getSourceId(),                 // sourceId
                                    0,                                    // chunkIndex (not applicable)
                                    "entity"                              // type
                                ));
                            }
                            
                            String contextStr = context.toString();
                            
                            // Step 5: Build prompt and call LLM
                            if (param.isOnlyNeedContext()) {
                                return CompletableFuture.completedFuture(new LightRAGQueryResult(
                                    contextStr,
                                    sourceChunks,
                                    QueryParam.Mode.GLOBAL,
                                    sourceChunks.size()
                                ));
                            }
                            
                            String prompt = buildPrompt(query, contextStr, param);
                            
                            if (param.isOnlyNeedPrompt()) {
                                return CompletableFuture.completedFuture(new LightRAGQueryResult(
                                    prompt,
                                    sourceChunks,
                                    QueryParam.Mode.GLOBAL,
                                    sourceChunks.size()
                                ));
                            }
                            
                            return llmFunction.apply(prompt, systemPrompt)
                                .thenApply(answer -> new LightRAGQueryResult(
                                    answer,
                                    sourceChunks,
                                    QueryParam.Mode.GLOBAL,
                                    sourceChunks.size()
                                ));
                        });
                });
            });
    }
}
