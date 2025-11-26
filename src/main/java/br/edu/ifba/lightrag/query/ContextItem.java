package br.edu.ifba.lightrag.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single item of context to be merged.
 * 
 * <p>Used by ContextMerger to track individual pieces of context
 * (entities, relations, chunks) with their metadata for smart merging.</p>
 * 
 * @param content the text content of this context item
 * @param type the type of context ("entity", "relation", "chunk")
 * @param sourceId unique identifier for the source (entity name, relation key, chunk ID)
 * @param filePath optional file path for provenance tracking
 * @param tokens estimated token count for this content
 */
public record ContextItem(
    @NotNull String content,
    @NotNull String type,
    @NotNull String sourceId,
    @Nullable String filePath,
    int tokens
) {
    /**
     * Creates an entity context item.
     * 
     * @param entityName the entity name
     * @param content the formatted entity content
     * @param tokens estimated token count
     * @return new ContextItem for entity
     */
    public static ContextItem entity(@NotNull String entityName, @NotNull String content, int tokens) {
        return new ContextItem(content, "entity", entityName, null, tokens);
    }
    
    /**
     * Creates a relation context item.
     * 
     * @param srcEntity source entity name
     * @param tgtEntity target entity name
     * @param content the formatted relation content
     * @param tokens estimated token count
     * @return new ContextItem for relation
     */
    public static ContextItem relation(@NotNull String srcEntity, @NotNull String tgtEntity, 
                                        @NotNull String content, int tokens) {
        return new ContextItem(content, "relation", srcEntity + "->" + tgtEntity, null, tokens);
    }
    
    /**
     * Creates a chunk context item.
     * 
     * @param chunkId the chunk identifier
     * @param content the chunk text
     * @param filePath optional source file path
     * @param tokens estimated token count
     * @return new ContextItem for chunk
     */
    public static ContextItem chunk(@NotNull String chunkId, @NotNull String content, 
                                     @Nullable String filePath, int tokens) {
        return new ContextItem(content, "chunk", chunkId, filePath, tokens);
    }
    
    /**
     * Checks if this is an entity context item.
     */
    public boolean isEntity() {
        return "entity".equals(type);
    }
    
    /**
     * Checks if this is a relation context item.
     */
    public boolean isRelation() {
        return "relation".equals(type);
    }
    
    /**
     * Checks if this is a chunk context item.
     */
    public boolean isChunk() {
        return "chunk".equals(type);
    }
}
