package br.edu.ifba.lightrag.deletion;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.ExtractionCache;
import br.edu.ifba.lightrag.core.Relation;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Helper class for determining entity/relation rebuild strategy during document deletion.
 * 
 * <p>This class analyzes which entities and relations are affected by document deletion
 * and determines whether they should be fully deleted or rebuilt from remaining sources.
 * 
 * <p>Classification rules:
 * <ul>
 *   <li>FULL_DELETE: Entity/relation has no sourceIds remaining after chunk removal</li>
 *   <li>REBUILD: Entity/relation has some sourceIds remaining, needs description update</li>
 *   <li>NO_CHANGE: Entity/relation is not affected by this deletion</li>
 * </ul>
 * 
 * @since spec-007
 */
public class EntityRebuildStrategy {
    
    private static final Logger LOG = Logger.getLogger(EntityRebuildStrategy.class);
    
    // Pattern to extract entity info from cached extraction results (JSON-like format)
    private static final Pattern ENTITY_PATTERN = Pattern.compile(
        "\\(\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Pattern to extract relationship info from cached extraction results
    private static final Pattern RELATION_PATTERN = Pattern.compile(
        "\\(\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*([\\d.]+)\\)",
        Pattern.CASE_INSENSITIVE
    );
    
    /**
     * Result of entity/relation classification.
     */
    public enum Action {
        /** Fully delete - no remaining sources */
        FULL_DELETE,
        /** Rebuild description from remaining sources */
        REBUILD,
        /** Not affected by this deletion */
        NO_CHANGE
    }
    
    /**
     * Classification result for an entity.
     */
    public record EntityClassification(
        @NotNull Entity entity,
        @NotNull Action action,
        @NotNull Set<String> remainingSourceIds,
        @NotNull Set<String> removedSourceIds
    ) {
        public EntityClassification {
            Objects.requireNonNull(entity, "entity must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(remainingSourceIds, "remainingSourceIds must not be null");
            Objects.requireNonNull(removedSourceIds, "removedSourceIds must not be null");
        }
    }
    
    /**
     * Classification result for a relation.
     */
    public record RelationClassification(
        @NotNull Relation relation,
        @NotNull Action action,
        @NotNull Set<String> remainingSourceIds,
        @NotNull Set<String> removedSourceIds
    ) {
        public RelationClassification {
            Objects.requireNonNull(relation, "relation must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(remainingSourceIds, "remainingSourceIds must not be null");
            Objects.requireNonNull(removedSourceIds, "removedSourceIds must not be null");
        }
    }
    
    /**
     * Extracted entity info from cached extraction.
     */
    public record ExtractedEntity(
        @NotNull String name,
        @NotNull String type,
        @NotNull String description
    ) {}
    
    /**
     * Extracted relation info from cached extraction.
     */
    public record ExtractedRelation(
        @NotNull String source,
        @NotNull String target,
        @NotNull String description,
        @NotNull String keywords,
        double weight
    ) {}
    
    /**
     * Classifies an entity based on which source chunks are being deleted.
     *
     * @param entity the entity to classify
     * @param deletedChunkIds chunk IDs being deleted
     * @return classification result with action and source tracking
     */
    @NotNull
    public EntityClassification classifyEntity(
        @NotNull Entity entity,
        @NotNull Set<String> deletedChunkIds
    ) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(deletedChunkIds, "deletedChunkIds must not be null");
        
        List<String> entitySourceIds = entity.getSourceChunkIds();
        if (entitySourceIds == null || entitySourceIds.isEmpty()) {
            // Entity has no source tracking - cannot determine impact
            LOG.warnf("Entity '%s' has no sourceChunkIds, treating as NO_CHANGE", entity.getEntityName());
            return new EntityClassification(entity, Action.NO_CHANGE, Set.of(), Set.of());
        }
        
        // Find which sources are being removed
        Set<String> removedSourceIds = entitySourceIds.stream()
            .filter(deletedChunkIds::contains)
            .collect(Collectors.toSet());
        
        // Find which sources remain
        Set<String> remainingSourceIds = entitySourceIds.stream()
            .filter(id -> !deletedChunkIds.contains(id))
            .collect(Collectors.toSet());
        
        // Determine action
        Action action;
        if (removedSourceIds.isEmpty()) {
            action = Action.NO_CHANGE;
        } else if (remainingSourceIds.isEmpty()) {
            action = Action.FULL_DELETE;
        } else {
            action = Action.REBUILD;
        }
        
        LOG.debugf("Entity '%s' classified as %s: removed=%d, remaining=%d",
            entity.getEntityName(), action, removedSourceIds.size(), remainingSourceIds.size());
        
        return new EntityClassification(entity, action, remainingSourceIds, removedSourceIds);
    }
    
    /**
     * Classifies a relation based on which source chunks are being deleted.
     *
     * @param relation the relation to classify
     * @param deletedChunkIds chunk IDs being deleted
     * @return classification result with action and source tracking
     */
    @NotNull
    public RelationClassification classifyRelation(
        @NotNull Relation relation,
        @NotNull Set<String> deletedChunkIds
    ) {
        Objects.requireNonNull(relation, "relation must not be null");
        Objects.requireNonNull(deletedChunkIds, "deletedChunkIds must not be null");
        
        List<String> relationSourceIds = relation.getSourceChunkIds();
        if (relationSourceIds == null || relationSourceIds.isEmpty()) {
            LOG.warnf("Relation '%s->%s' has no sourceChunkIds, treating as NO_CHANGE",
                relation.getSrcId(), relation.getTgtId());
            return new RelationClassification(relation, Action.NO_CHANGE, Set.of(), Set.of());
        }
        
        // Find which sources are being removed
        Set<String> removedSourceIds = relationSourceIds.stream()
            .filter(deletedChunkIds::contains)
            .collect(Collectors.toSet());
        
        // Find which sources remain
        Set<String> remainingSourceIds = relationSourceIds.stream()
            .filter(id -> !deletedChunkIds.contains(id))
            .collect(Collectors.toSet());
        
        // Determine action
        Action action;
        if (removedSourceIds.isEmpty()) {
            action = Action.NO_CHANGE;
        } else if (remainingSourceIds.isEmpty()) {
            action = Action.FULL_DELETE;
        } else {
            action = Action.REBUILD;
        }
        
        LOG.debugf("Relation '%s->%s' classified as %s: removed=%d, remaining=%d",
            relation.getSrcId(), relation.getTgtId(), action,
            removedSourceIds.size(), remainingSourceIds.size());
        
        return new RelationClassification(relation, action, remainingSourceIds, removedSourceIds);
    }
    
    /**
     * Rebuilds an entity description from cached extractions.
     * 
     * <p>This method aggregates descriptions from remaining source chunks using
     * the cached LLM extraction results, avoiding expensive re-extraction.
     *
     * @param entityName the entity name to rebuild
     * @param cacheEntries cached extractions from remaining source chunks
     * @return rebuilt description, or empty string if no relevant data found
     */
    @NotNull
    public String rebuildEntityDescription(
        @NotNull String entityName,
        @NotNull List<ExtractionCache> cacheEntries
    ) {
        Objects.requireNonNull(entityName, "entityName must not be null");
        Objects.requireNonNull(cacheEntries, "cacheEntries must not be null");
        
        List<String> descriptions = new ArrayList<>();
        String normalizedName = entityName.toLowerCase().trim();
        
        for (ExtractionCache cache : cacheEntries) {
            String result = cache.result();
            if (result == null || result.isBlank()) {
                continue;
            }
            
            // Parse cached extraction to find entity descriptions
            List<ExtractedEntity> extracted = parseEntitiesFromCache(result);
            for (ExtractedEntity entity : extracted) {
                if (entity.name().toLowerCase().trim().equals(normalizedName)) {
                    if (!entity.description().isBlank()) {
                        descriptions.add(entity.description());
                    }
                }
            }
        }
        
        if (descriptions.isEmpty()) {
            LOG.debugf("No descriptions found in cache for entity '%s'", entityName);
            return "";
        }
        
        // Concatenate with separator (could be enhanced to use LLM summarization)
        String rebuilt = String.join(" | ", descriptions);
        LOG.debugf("Rebuilt description for entity '%s' from %d sources",
            entityName, descriptions.size());
        
        return rebuilt;
    }
    
    /**
     * Rebuilds a relation description from cached extractions.
     *
     * @param srcId the source entity ID
     * @param tgtId the target entity ID
     * @param cacheEntries cached extractions from remaining source chunks
     * @return rebuilt description, or empty string if no relevant data found
     */
    @NotNull
    public String rebuildRelationDescription(
        @NotNull String srcId,
        @NotNull String tgtId,
        @NotNull List<ExtractionCache> cacheEntries
    ) {
        Objects.requireNonNull(srcId, "srcId must not be null");
        Objects.requireNonNull(tgtId, "tgtId must not be null");
        Objects.requireNonNull(cacheEntries, "cacheEntries must not be null");
        
        List<String> descriptions = new ArrayList<>();
        String normalizedSrc = srcId.toLowerCase().trim();
        String normalizedTgt = tgtId.toLowerCase().trim();
        
        for (ExtractionCache cache : cacheEntries) {
            String result = cache.result();
            if (result == null || result.isBlank()) {
                continue;
            }
            
            // Parse cached extraction to find relation descriptions
            List<ExtractedRelation> extracted = parseRelationsFromCache(result);
            for (ExtractedRelation relation : extracted) {
                if (relation.source().toLowerCase().trim().equals(normalizedSrc) &&
                    relation.target().toLowerCase().trim().equals(normalizedTgt)) {
                    if (!relation.description().isBlank()) {
                        descriptions.add(relation.description());
                    }
                }
            }
        }
        
        if (descriptions.isEmpty()) {
            LOG.debugf("No descriptions found in cache for relation '%s->%s'", srcId, tgtId);
            return "";
        }
        
        String rebuilt = String.join(" | ", descriptions);
        LOG.debugf("Rebuilt description for relation '%s->%s' from %d sources",
            srcId, tgtId, descriptions.size());
        
        return rebuilt;
    }
    
    /**
     * Parses entity information from cached LLM extraction result.
     * 
     * <p>Expected format: ("entity_name", "entity_type", "description")
     *
     * @param cacheResult the raw cached extraction result
     * @return list of extracted entities
     */
    @NotNull
    public List<ExtractedEntity> parseEntitiesFromCache(@NotNull String cacheResult) {
        List<ExtractedEntity> entities = new ArrayList<>();
        
        Matcher matcher = ENTITY_PATTERN.matcher(cacheResult);
        while (matcher.find()) {
            String name = matcher.group(1);
            String type = matcher.group(2);
            String description = matcher.group(3);
            entities.add(new ExtractedEntity(name, type, description));
        }
        
        return entities;
    }
    
    /**
     * Parses relation information from cached LLM extraction result.
     * 
     * <p>Expected format: ("source", "target", "description", "keywords", weight)
     *
     * @param cacheResult the raw cached extraction result
     * @return list of extracted relations
     */
    @NotNull
    public List<ExtractedRelation> parseRelationsFromCache(@NotNull String cacheResult) {
        List<ExtractedRelation> relations = new ArrayList<>();
        
        Matcher matcher = RELATION_PATTERN.matcher(cacheResult);
        while (matcher.find()) {
            String source = matcher.group(1);
            String target = matcher.group(2);
            String description = matcher.group(3);
            String keywords = matcher.group(4);
            double weight = Double.parseDouble(matcher.group(5));
            relations.add(new ExtractedRelation(source, target, description, keywords, weight));
        }
        
        return relations;
    }
    
    /**
     * Groups cache entries by chunk ID for efficient lookup.
     *
     * @param cacheEntries list of cache entries
     * @return map from chunk ID (as string) to cache entries
     */
    @NotNull
    public Map<String, List<ExtractionCache>> groupCacheByChunkId(
        @NotNull List<ExtractionCache> cacheEntries
    ) {
        return cacheEntries.stream()
            .filter(cache -> cache.chunkId() != null)
            .collect(Collectors.groupingBy(cache -> cache.chunkId().toString()));
    }
    
    /**
     * Gets cache entries for specific chunk IDs.
     *
     * @param allCacheEntries all available cache entries
     * @param chunkIds chunk IDs to filter by (as strings)
     * @return filtered cache entries
     */
    @NotNull
    public List<ExtractionCache> getCacheEntriesForChunks(
        @NotNull List<ExtractionCache> allCacheEntries,
        @NotNull Set<String> chunkIds
    ) {
        return allCacheEntries.stream()
            .filter(cache -> cache.chunkId() != null && chunkIds.contains(cache.chunkId().toString()))
            .collect(Collectors.toList());
    }
}
