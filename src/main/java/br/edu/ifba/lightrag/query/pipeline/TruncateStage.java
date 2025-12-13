package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.query.ContextItem;
import br.edu.ifba.lightrag.utils.TokenUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline stage that applies token budgets to truncate candidates.
 * 
 * <p>This stage:</p>
 * <ol>
 *   <li>Converts raw candidates to ContextItems with token counts</li>
 *   <li>Applies per-type token budgets (chunks, entities, relations)</li>
 *   <li>Truncates items that exceed budgets</li>
 *   <li>Stores truncated items in context</li>
 * </ol>
 * 
 * <h2>Token Budget Allocation:</h2>
 * <p>The total token budget is split among source types using configurable ratios:</p>
 * <ul>
 *   <li>Chunks: 30% (default)</li>
 *   <li>Entities: 40% (default)</li>
 *   <li>Relations: 30% (default)</li>
 * </ul>
 * 
 * @since spec-008
 */
public class TruncateStage implements PipelineStage {
    
    private static final Logger logger = LoggerFactory.getLogger(TruncateStage.class);
    private static final String STAGE_NAME = "truncate";
    
    // Default token budgets
    private static final int DEFAULT_MAX_TOKENS = 4000;
    private static final double DEFAULT_CHUNK_RATIO = 0.3;
    private static final double DEFAULT_ENTITY_RATIO = 0.4;
    private static final double DEFAULT_RELATION_RATIO = 0.3;
    
    private final int maxTokens;
    private final double chunkRatio;
    private final double entityRatio;
    private final double relationRatio;
    
    /**
     * Creates a TruncateStage with default configuration.
     */
    public TruncateStage() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_CHUNK_RATIO, DEFAULT_ENTITY_RATIO, DEFAULT_RELATION_RATIO);
    }
    
    /**
     * Creates a TruncateStage from LightRAGExtractionConfig.
     */
    public TruncateStage(@NotNull LightRAGExtractionConfig config) {
        this(
            config.query().context().maxTokens(),
            config.query().context().chunkBudgetRatio(),
            config.query().context().entityBudgetRatio(),
            config.query().context().relationBudgetRatio()
        );
    }
    
    /**
     * Creates a TruncateStage with custom configuration.
     *
     * @param maxTokens Total token budget
     * @param chunkRatio Ratio of budget for chunks (0.0-1.0)
     * @param entityRatio Ratio of budget for entities (0.0-1.0)
     * @param relationRatio Ratio of budget for relations (0.0-1.0)
     */
    public TruncateStage(int maxTokens, double chunkRatio, double entityRatio, double relationRatio) {
        this.maxTokens = maxTokens;
        this.chunkRatio = chunkRatio;
        this.entityRatio = entityRatio;
        this.relationRatio = relationRatio;
        
        // Validate ratios sum to approximately 1.0
        double sum = chunkRatio + entityRatio + relationRatio;
        if (Math.abs(sum - 1.0) > 0.01) {
            logger.warn("Token budget ratios sum to {} (expected 1.0), results may be suboptimal", sum);
        }
    }
    
    @Override
    public CompletableFuture<PipelineContext> process(@NotNull PipelineContext context) {
        logger.debug("Starting truncation with maxTokens={}", maxTokens);
        
        // Calculate per-type budgets
        int chunkBudget = (int) (maxTokens * chunkRatio);
        int entityBudget = (int) (maxTokens * entityRatio);
        int relationBudget = (int) (maxTokens * relationRatio);
        
        logger.debug("Token budgets - chunks: {}, entities: {}, relations: {}", 
                chunkBudget, entityBudget, relationBudget);
        
        // Truncate each type within its budget
        List<ContextItem> truncatedChunks = truncateChunks(context.getChunkCandidates(), chunkBudget);
        List<ContextItem> truncatedEntities = truncateEntities(context.getEntityCandidates(), entityBudget);
        List<ContextItem> truncatedRelations = truncateRelations(context.getRelationCandidates(), relationBudget);
        
        // Store in context
        context.setTruncatedChunks(truncatedChunks);
        context.setTruncatedEntities(truncatedEntities);
        context.setTruncatedRelations(truncatedRelations);
        
        // Calculate actual token counts
        int chunkTokens = truncatedChunks.stream().mapToInt(ContextItem::tokens).sum();
        int entityTokens = truncatedEntities.stream().mapToInt(ContextItem::tokens).sum();
        int relationTokens = truncatedRelations.stream().mapToInt(ContextItem::tokens).sum();
        
        context.setChunkTokens(chunkTokens);
        context.setEntityTokens(entityTokens);
        context.setRelationTokens(relationTokens);
        context.setTotalTokens(chunkTokens + entityTokens + relationTokens);
        
        logger.debug("Truncation complete - chunks: {} items ({} tokens), entities: {} items ({} tokens), relations: {} items ({} tokens)",
                truncatedChunks.size(), chunkTokens,
                truncatedEntities.size(), entityTokens,
                truncatedRelations.size(), relationTokens);
        
        return CompletableFuture.completedFuture(context);
    }
    
    /**
     * Truncates chunks within the token budget.
     */
    private List<ContextItem> truncateChunks(@NotNull List<SourceChunk> chunks, int budget) {
        List<ContextItem> result = new ArrayList<>();
        int usedTokens = 0;
        
        for (SourceChunk chunk : chunks) {
            String content = formatChunkContent(chunk);
            int tokens = TokenUtil.estimateTokens(content);
            
            if (usedTokens + tokens > budget) {
                logger.trace("Chunk truncated at budget limit: {} + {} > {}", usedTokens, tokens, budget);
                break;
            }
            
            result.add(new ContextItem(
                    content,
                    "chunk",
                    chunk.chunkId(),
                    null,
                    tokens
            ));
            usedTokens += tokens;
        }
        
        return result;
    }
    
    /**
     * Formats chunk content with optional document citation.
     */
    private String formatChunkContent(@NotNull SourceChunk chunk) {
        if (chunk.documentId() != null && !chunk.documentId().isEmpty()) {
            return String.format("[%s] %s", chunk.documentId(), chunk.content());
        }
        return chunk.content();
    }
    
    /**
     * Truncates entities within the token budget.
     */
    private List<ContextItem> truncateEntities(@NotNull List<Entity> entities, int budget) {
        List<ContextItem> result = new ArrayList<>();
        int usedTokens = 0;
        
        for (Entity entity : entities) {
            String content = formatEntityContent(entity);
            int tokens = TokenUtil.estimateTokens(content);
            
            if (usedTokens + tokens > budget) {
                logger.trace("Entity truncated at budget limit: {} + {} > {}", usedTokens, tokens, budget);
                break;
            }
            
            result.add(new ContextItem(
                    content,
                    "entity",
                    entity.getEntityName(),
                    entity.getFilePath(),
                    tokens
            ));
            usedTokens += tokens;
        }
        
        return result;
    }
    
    /**
     * Formats entity content for context.
     */
    private String formatEntityContent(@NotNull Entity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity.getEntityName());
        
        if (entity.getEntityType() != null && !entity.getEntityType().isEmpty()) {
            sb.append(" (").append(entity.getEntityType()).append(")");
        }
        
        if (entity.getDescription() != null && !entity.getDescription().isEmpty()) {
            sb.append(": ").append(entity.getDescription());
        }
        
        return sb.toString();
    }
    
    /**
     * Truncates relations within the token budget.
     */
    private List<ContextItem> truncateRelations(@NotNull List<Relation> relations, int budget) {
        List<ContextItem> result = new ArrayList<>();
        int usedTokens = 0;
        
        for (Relation relation : relations) {
            String content = formatRelationContent(relation);
            int tokens = TokenUtil.estimateTokens(content);
            
            if (usedTokens + tokens > budget) {
                logger.trace("Relation truncated at budget limit: {} + {} > {}", usedTokens, tokens, budget);
                break;
            }
            
            String relationId = relation.getSrcId() + "->" + relation.getTgtId();
            result.add(new ContextItem(
                    content,
                    "relation",
                    relationId,
                    null,
                    tokens
            ));
            usedTokens += tokens;
        }
        
        return result;
    }
    
    /**
     * Formats relation content for context.
     */
    private String formatRelationContent(@NotNull Relation relation) {
        StringBuilder sb = new StringBuilder();
        sb.append(relation.getSrcId())
          .append(" -> ")
          .append(relation.getTgtId());
        
        if (relation.getDescription() != null && !relation.getDescription().isEmpty()) {
            sb.append(": ").append(relation.getDescription());
        }
        
        return sb.toString();
    }
    
    @Override
    public String getName() {
        return STAGE_NAME;
    }
    
    @Override
    public boolean shouldSkip(@NotNull PipelineContext context) {
        // Skip if no candidates to truncate
        return !context.hasCandidates();
    }
}
