package br.edu.ifba.lightrag.merge;

import br.edu.ifba.lightrag.core.DescriptionSummarizer;
import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of EntityMergeService for merging duplicate entities.
 * 
 * <p>This service handles the complete entity merge lifecycle:
 * <ul>
 *   <li>Validating source and target entities exist</li>
 *   <li>Collecting and redirecting relationships</li>
 *   <li>Merging entity descriptions using configured strategy</li>
 *   <li>Removing self-loop relations created by the merge</li>
 *   <li>Deduplicating relations with the same src-tgt pair</li>
 *   <li>Cleaning up source entities and their embeddings</li>
 * </ul>
 * 
 * <h2>Self-Loop Prevention (T063):</h2>
 * <p>When entities A and B are merged into B, any relation A->B or B->A becomes
 * B->B (a self-loop). These are automatically filtered out during the merge.</p>
 * 
 * <h2>Token Tracking (T066):</h2>
 * <p>When using LLM_SUMMARIZE strategy, token usage is tracked via the
 * injected TokenTracker for inclusion in response headers.</p>
 * 
 * <h2>MDC Context:</h2>
 * <ul>
 *   <li><code>merge.projectId</code> - The project ID</li>
 *   <li><code>merge.targetEntity</code> - The target entity name</li>
 *   <li><code>merge.strategy</code> - The merge strategy being used</li>
 *   <li><code>merge.phase</code> - Current merge phase (validate, collect, redirect, merge, cleanup)</li>
 * </ul>
 * 
 * @see EntityMergeService
 * @see MergeStrategy
 * @see RelationshipRedirector
 * @since spec-007
 */
@ApplicationScoped
public class EntityMergeServiceImpl implements EntityMergeService {
    
    private static final Logger LOG = LoggerFactory.getLogger(EntityMergeServiceImpl.class);
    
    private static final String MDC_PROJECT_ID = "merge.projectId";
    private static final String MDC_TARGET_ENTITY = "merge.targetEntity";
    private static final String MDC_STRATEGY = "merge.strategy";
    private static final String MDC_PHASE = "merge.phase";
    
    private final GraphStorage graphStorage;
    private final VectorStorage vectorStorage;
    private final DescriptionSummarizer descriptionSummarizer;
    private final RelationshipRedirector relationshipRedirector;
    
    @Inject
    public EntityMergeServiceImpl(
        GraphStorage graphStorage,
        VectorStorage vectorStorage,
        DescriptionSummarizer descriptionSummarizer
    ) {
        this.graphStorage = graphStorage;
        this.vectorStorage = vectorStorage;
        this.descriptionSummarizer = descriptionSummarizer;
        this.relationshipRedirector = new RelationshipRedirector();
    }
    
    /**
     * Constructor for testing with custom RelationshipRedirector.
     */
    EntityMergeServiceImpl(
        GraphStorage graphStorage,
        VectorStorage vectorStorage,
        DescriptionSummarizer descriptionSummarizer,
        RelationshipRedirector relationshipRedirector
    ) {
        this.graphStorage = graphStorage;
        this.vectorStorage = vectorStorage;
        this.descriptionSummarizer = descriptionSummarizer;
        this.relationshipRedirector = relationshipRedirector;
    }
    
    @Override
    @Transactional
    public MergeResult mergeEntities(
        @NotNull String projectId,
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity,
        @NotNull MergeStrategy strategy,
        @Nullable Map<String, Object> targetEntityData
    ) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(sourceEntities, "sourceEntities must not be null");
        Objects.requireNonNull(targetEntity, "targetEntity must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        
        long startTime = System.currentTimeMillis();
        
        try {
            setMDC(projectId, targetEntity, strategy.name(), "validate");
            
            // Validate inputs
            List<String> validationErrors = validateMerge(projectId, sourceEntities, targetEntity);
            if (!validationErrors.isEmpty()) {
                LOG.warn("Merge validation failed: {}", validationErrors);
                throw new IllegalArgumentException("Merge validation failed: " + String.join(", ", validationErrors));
            }
            
            LOG.info("Starting entity merge: {} source entities -> '{}' with strategy {}",
                sourceEntities.size(), targetEntity, strategy);
            
            // Step 1: Fetch all source entities
            setMDC(projectId, targetEntity, strategy.name(), "collect");
            List<Entity> sourceEntityObjects = fetchEntities(projectId, sourceEntities);
            LOG.debug("Fetched {} source entities", sourceEntityObjects.size());
            
            // Step 2: Identify entities to merge (exclude target if it's in sourceEntities)
            Set<String> entitiesToMerge = sourceEntities.stream()
                .filter(name -> !name.equalsIgnoreCase(targetEntity))
                .collect(Collectors.toSet());
            
            // If all source entities are the target, no merge needed
            if (entitiesToMerge.isEmpty()) {
                LOG.info("No-op merge: all source entities are the target");
                Entity existingTarget = graphStorage.getEntity(projectId, targetEntity).join();
                return MergeResult.noOp(existingTarget);
            }
            
            // Step 3: Collect all relations involving source entities
            List<Relation> allRelations = collectRelations(projectId, sourceEntities);
            LOG.debug("Collected {} relations involving source entities", allRelations.size());
            
            // Step 4: Redirect relations and handle deduplication/self-loops
            setMDC(projectId, targetEntity, strategy.name(), "redirect");
            RelationshipRedirector.RedirectResult redirectResult = relationshipRedirector.redirectAndDeduplicate(
                allRelations,
                new HashSet<>(sourceEntities),
                targetEntity,
                strategy
            );
            LOG.debug("Redirect result: {} redirected, {} deduped, {} self-loops filtered",
                redirectResult.redirectedRelations().size(),
                redirectResult.duplicatesMerged(),
                redirectResult.selfLoopsFiltered());
            
            // Step 5: Merge descriptions
            setMDC(projectId, targetEntity, strategy.name(), "merge");
            String mergedDescription = mergeDescriptions(
                projectId,
                targetEntity,
                sourceEntityObjects,
                strategy
            );
            
            // Step 6: Merge source chunk IDs from all entities
            List<String> mergedSourceChunkIds = mergeSourceChunkIds(sourceEntityObjects);
            
            // Step 7: Determine entity type (prefer non-null, use target's type if available)
            String mergedType = determineEntityType(sourceEntityObjects, targetEntityData);
            
            // Step 8: Create or update target entity
            Entity mergedEntity = Entity.builder()
                .entityName(targetEntity)
                .entityType(mergedType)
                .description(mergedDescription)
                .sourceChunkIds(mergedSourceChunkIds)
                .build();
            
            // Step 9: Delete old relations for source entities
            Set<String> relationKeysToDelete = allRelations.stream()
                .map(r -> r.getSrcId().toLowerCase() + "->" + r.getTgtId().toLowerCase())
                .collect(Collectors.toSet());
            
            if (!relationKeysToDelete.isEmpty()) {
                graphStorage.deleteRelations(projectId, relationKeysToDelete).join();
            }
            
            // Step 10: Upsert the merged target entity
            graphStorage.upsertEntity(projectId, mergedEntity).join();
            
            // Step 11: Upsert redirected relations
            if (!redirectResult.redirectedRelations().isEmpty()) {
                graphStorage.upsertRelations(projectId, redirectResult.redirectedRelations()).join();
            }
            
            // Step 12: Delete source entities (excluding target)
            setMDC(projectId, targetEntity, strategy.name(), "cleanup");
            int deletedEntities = 0;
            if (!entitiesToMerge.isEmpty()) {
                deletedEntities = graphStorage.deleteEntities(projectId, entitiesToMerge).join();
                
                // Step 13: Delete embeddings for source entities
                vectorStorage.deleteEntityEmbeddings(projectId, entitiesToMerge).join();
                LOG.debug("Deleted {} entities and their embeddings", deletedEntities);
            }
            
            MergeResult result = MergeResult.builder()
                .targetEntity(mergedEntity)
                .relationsRedirected(redirectResult.redirectedRelations().size())
                .sourceEntitiesDeleted(deletedEntities)
                .relationsDeduped(redirectResult.duplicatesMerged())
                .build();
            
            long duration = System.currentTimeMillis() - startTime;
            setMDC(projectId, targetEntity, strategy.name(), "complete");
            LOG.info("Entity merge completed - duration={}ms, relations[redirected={}, deduped={}], entities[deleted={}], selfLoops[filtered={}]",
                duration,
                result.relationsRedirected(),
                result.relationsDeduped(),
                result.sourceEntitiesDeleted(),
                redirectResult.selfLoopsFiltered());
            
            return result;
            
        } catch (IllegalArgumentException e) {
            // Re-throw validation errors without wrapping
            throw e;
        } catch (Exception e) {
            setMDC(projectId, targetEntity, strategy.name(), "error");
            LOG.error("Entity merge failed for target '{}': {}", targetEntity, e.getMessage(), e);
            throw new IllegalStateException("Entity merge failed: " + e.getMessage(), e);
        } finally {
            clearMDC();
        }
    }
    
    @Override
    public List<String> validateMerge(
        @NotNull String projectId,
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity
    ) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(sourceEntities, "sourceEntities must not be null");
        Objects.requireNonNull(targetEntity, "targetEntity must not be null");
        
        List<String> errors = new ArrayList<>();
        
        // Check for empty inputs
        if (projectId.isBlank()) {
            errors.add("projectId must not be blank");
        }
        
        if (sourceEntities.isEmpty()) {
            errors.add("sourceEntities must not be empty");
        }
        
        if (targetEntity.isBlank()) {
            errors.add("targetEntity must not be blank");
        }
        
        // Check for null elements in source list
        for (int i = 0; i < sourceEntities.size(); i++) {
            if (sourceEntities.get(i) == null || sourceEntities.get(i).isBlank()) {
                errors.add("sourceEntities[" + i + "] must not be null or blank");
            }
        }
        
        // Check for duplicates in source list
        Set<String> uniqueNames = new HashSet<>();
        for (String name : sourceEntities) {
            if (name != null && !uniqueNames.add(name.toLowerCase())) {
                errors.add("Duplicate entity in sourceEntities: " + name);
            }
        }
        
        // Verify entities exist
        if (errors.isEmpty()) {
            for (String entityName : sourceEntities) {
                if (!entityExists(projectId, entityName)) {
                    errors.add("Entity not found: " + entityName);
                }
            }
        }
        
        return errors;
    }
    
    @Override
    public boolean entityExists(@NotNull String projectId, @NotNull String entityName) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(entityName, "entityName must not be null");
        
        try {
            Entity entity = graphStorage.getEntity(projectId, entityName).join();
            return entity != null;
        } catch (Exception e) {
            LOG.warn("Error checking entity existence for '{}': {}", entityName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Sets MDC context for structured logging.
     */
    private void setMDC(String projectId, String targetEntity, String strategy, String phase) {
        MDC.put(MDC_PROJECT_ID, projectId);
        MDC.put(MDC_TARGET_ENTITY, targetEntity);
        MDC.put(MDC_STRATEGY, strategy);
        MDC.put(MDC_PHASE, phase);
    }
    
    /**
     * Clears MDC context.
     */
    private void clearMDC() {
        MDC.remove(MDC_PROJECT_ID);
        MDC.remove(MDC_TARGET_ENTITY);
        MDC.remove(MDC_STRATEGY);
        MDC.remove(MDC_PHASE);
    }
    
    /**
     * Fetches all entities by name from the graph.
     */
    private List<Entity> fetchEntities(String projectId, List<String> entityNames) {
        return graphStorage.getEntities(projectId, entityNames).join();
    }
    
    /**
     * Collects all relations involving any of the specified entities.
     */
    private List<Relation> collectRelations(String projectId, List<String> entityNames) {
        Set<String> seen = new HashSet<>();
        List<Relation> allRelations = new ArrayList<>();
        
        for (String entityName : entityNames) {
            List<Relation> relations = graphStorage.getRelationsForEntity(projectId, entityName).join();
            for (Relation r : relations) {
                // Use a composite key to deduplicate
                String key = r.getSrcId().toLowerCase() + "->" + r.getTgtId().toLowerCase();
                if (seen.add(key)) {
                    allRelations.add(r);
                }
            }
        }
        
        return allRelations;
    }
    
    /**
     * Merges descriptions from all source entities using the specified strategy.
     * 
     * For LLM_SUMMARIZE, uses the DescriptionSummarizer and tracks tokens.
     */
    private String mergeDescriptions(
        String projectId,
        String targetEntity,
        List<Entity> sourceEntities,
        MergeStrategy strategy
    ) {
        if (sourceEntities.isEmpty()) {
            return "";
        }
        
        // Collect all unique descriptions
        List<String> descriptions = sourceEntities.stream()
            .map(Entity::getDescription)
            .filter(d -> d != null && !d.isBlank())
            .distinct()
            .toList();
        
        if (descriptions.isEmpty()) {
            return "";
        }
        
        if (descriptions.size() == 1) {
            return descriptions.get(0);
        }
        
        return switch (strategy) {
            case CONCATENATE -> String.join(strategy.getSeparator(), descriptions);
            case KEEP_FIRST -> descriptions.get(0);
            case KEEP_LONGEST -> descriptions.stream()
                .max((a, b) -> Integer.compare(a.length(), b.length()))
                .orElse(descriptions.get(0));
            case LLM_SUMMARIZE -> {
                // Use the description summarizer (tracks tokens internally)
                String entityType = sourceEntities.stream()
                    .map(Entity::getEntityType)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .orElse(null);
                
                try {
                    LOG.debug("Using LLM to summarize {} descriptions for entity '{}'", 
                        descriptions.size(), targetEntity);
                    yield descriptionSummarizer.summarize(targetEntity, entityType, descriptions, projectId).join();
                } catch (Exception e) {
                    LOG.warn("LLM summarization failed for entity '{}', falling back to concatenation: {}", 
                        targetEntity, e.getMessage());
                    yield String.join(" | ", descriptions);
                }
            }
        };
    }
    
    /**
     * Merges source chunk IDs from all entities, deduplicating and respecting max limit.
     */
    private List<String> mergeSourceChunkIds(List<Entity> entities) {
        List<String> merged = new ArrayList<>();
        
        for (Entity entity : entities) {
            for (String chunkId : entity.getSourceChunkIds()) {
                if (!merged.contains(chunkId)) {
                    merged.add(chunkId);
                }
            }
        }
        
        // Respect max limit with FIFO eviction
        while (merged.size() > Entity.MAX_SOURCE_CHUNK_IDS) {
            merged.remove(0);
        }
        
        return merged;
    }
    
    /**
     * Determines the entity type for the merged entity.
     */
    private String determineEntityType(List<Entity> entities, @Nullable Map<String, Object> targetEntityData) {
        // First check targetEntityData override
        if (targetEntityData != null && targetEntityData.containsKey("type")) {
            Object typeValue = targetEntityData.get("type");
            if (typeValue != null) {
                return typeValue.toString();
            }
        }
        
        // Otherwise find first non-null type from entities
        return entities.stream()
            .map(Entity::getEntityType)
            .filter(t -> t != null && !t.isBlank())
            .findFirst()
            .orElse(null);
    }
}
