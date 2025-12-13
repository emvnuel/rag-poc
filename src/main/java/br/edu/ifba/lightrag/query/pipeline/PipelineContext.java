package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.query.ContextItem;
import br.edu.ifba.lightrag.query.KeywordResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Context object that flows through the query pipeline stages.
 * 
 * <p>Each stage can read from and write to this context, allowing data
 * to be passed between stages without tight coupling. This design enables:
 * <ul>
 *   <li>Stages to be tested independently</li>
 *   <li>Easy addition of new stages</li>
 *   <li>Clear data flow through the pipeline</li>
 * </ul>
 * 
 * <h2>Pipeline Flow:</h2>
 * <pre>
 * Query → [Search] → [Truncate] → [Merge] → [BuildContext] → Response
 *           ↓            ↓           ↓            ↓
 *      candidates   truncated    merged      finalContext
 * </pre>
 * 
 * @since spec-008
 */
public final class PipelineContext {
    
    // === Input data (set at pipeline start) ===
    
    private final String query;
    private final QueryParam param;
    private final Object queryEmbedding;
    
    // === Intermediate data (set by stages) ===
    
    /** Keywords extracted from query (set by SearchStage if enabled) */
    @Nullable
    private KeywordResult keywords;
    
    /** Raw search results - chunks */
    private List<SourceChunk> chunkCandidates = new ArrayList<>();
    
    /** Raw search results - entities */
    private List<Entity> entityCandidates = new ArrayList<>();
    
    /** Raw search results - relations */
    private List<Relation> relationCandidates = new ArrayList<>();
    
    /** Truncated results after applying token budgets */
    private List<ContextItem> truncatedChunks = new ArrayList<>();
    private List<ContextItem> truncatedEntities = new ArrayList<>();
    private List<ContextItem> truncatedRelations = new ArrayList<>();
    
    /** Merged context items (after round-robin interleaving) */
    private List<ContextItem> mergedItems = new ArrayList<>();
    
    /** Token counts per category */
    private int chunkTokens = 0;
    private int entityTokens = 0;
    private int relationTokens = 0;
    private int totalTokens = 0;
    
    // === Output data (set by final stage) ===
    
    /** Final formatted context string */
    private String finalContext = "";
    
    /** Final prompt (context + query + conversation history) */
    private String finalPrompt = "";
    
    /** All source chunks for response metadata */
    private List<SourceChunk> allSources = new ArrayList<>();
    
    // === Custom attributes for extensibility ===
    
    private final Map<String, Object> attributes = new HashMap<>();
    
    /**
     * Creates a new pipeline context.
     * 
     * @param query The original user query
     * @param param Query parameters
     * @param queryEmbedding Pre-computed query embedding (can be null if not yet computed)
     */
    public PipelineContext(
            @NotNull String query,
            @NotNull QueryParam param,
            @Nullable Object queryEmbedding) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.param = Objects.requireNonNull(param, "param must not be null");
        this.queryEmbedding = queryEmbedding;
    }
    
    // === Getters for input data ===
    
    @NotNull
    public String getQuery() {
        return query;
    }
    
    @NotNull
    public QueryParam getParam() {
        return param;
    }
    
    @Nullable
    public Object getQueryEmbedding() {
        return queryEmbedding;
    }
    
    @NotNull
    public String getProjectId() {
        return param.getProjectId();
    }
    
    // === Keywords ===
    
    @Nullable
    public KeywordResult getKeywords() {
        return keywords;
    }
    
    public void setKeywords(@Nullable KeywordResult keywords) {
        this.keywords = keywords;
    }
    
    /**
     * Gets low-level keywords for LOCAL mode search.
     */
    @NotNull
    public List<String> getLowLevelKeywords() {
        return keywords != null ? keywords.lowLevelKeywords() : List.of();
    }
    
    /**
     * Gets high-level keywords for GLOBAL mode search.
     */
    @NotNull
    public List<String> getHighLevelKeywords() {
        return keywords != null ? keywords.highLevelKeywords() : List.of();
    }
    
    // === Chunk candidates ===
    
    @NotNull
    public List<SourceChunk> getChunkCandidates() {
        return chunkCandidates;
    }
    
    public void setChunkCandidates(@NotNull List<SourceChunk> chunks) {
        this.chunkCandidates = new ArrayList<>(chunks);
    }
    
    public void addChunkCandidate(@NotNull SourceChunk chunk) {
        this.chunkCandidates.add(chunk);
    }
    
    // === Entity candidates ===
    
    @NotNull
    public List<Entity> getEntityCandidates() {
        return entityCandidates;
    }
    
    public void setEntityCandidates(@NotNull List<Entity> entities) {
        this.entityCandidates = new ArrayList<>(entities);
    }
    
    public void addEntityCandidate(@NotNull Entity entity) {
        this.entityCandidates.add(entity);
    }
    
    // === Relation candidates ===
    
    @NotNull
    public List<Relation> getRelationCandidates() {
        return relationCandidates;
    }
    
    public void setRelationCandidates(@NotNull List<Relation> relations) {
        this.relationCandidates = new ArrayList<>(relations);
    }
    
    public void addRelationCandidate(@NotNull Relation relation) {
        this.relationCandidates.add(relation);
    }
    
    // === Truncated items ===
    
    @NotNull
    public List<ContextItem> getTruncatedChunks() {
        return truncatedChunks;
    }
    
    public void setTruncatedChunks(@NotNull List<ContextItem> items) {
        this.truncatedChunks = new ArrayList<>(items);
    }
    
    @NotNull
    public List<ContextItem> getTruncatedEntities() {
        return truncatedEntities;
    }
    
    public void setTruncatedEntities(@NotNull List<ContextItem> items) {
        this.truncatedEntities = new ArrayList<>(items);
    }
    
    @NotNull
    public List<ContextItem> getTruncatedRelations() {
        return truncatedRelations;
    }
    
    public void setTruncatedRelations(@NotNull List<ContextItem> items) {
        this.truncatedRelations = new ArrayList<>(items);
    }
    
    // === Merged items ===
    
    @NotNull
    public List<ContextItem> getMergedItems() {
        return mergedItems;
    }
    
    public void setMergedItems(@NotNull List<ContextItem> items) {
        this.mergedItems = new ArrayList<>(items);
    }
    
    // === Token counts ===
    
    public int getChunkTokens() {
        return chunkTokens;
    }
    
    public void setChunkTokens(int tokens) {
        this.chunkTokens = tokens;
    }
    
    public int getEntityTokens() {
        return entityTokens;
    }
    
    public void setEntityTokens(int tokens) {
        this.entityTokens = tokens;
    }
    
    public int getRelationTokens() {
        return relationTokens;
    }
    
    public void setRelationTokens(int tokens) {
        this.relationTokens = tokens;
    }
    
    public int getTotalTokens() {
        return totalTokens;
    }
    
    public void setTotalTokens(int tokens) {
        this.totalTokens = tokens;
    }
    
    // === Final output ===
    
    @NotNull
    public String getFinalContext() {
        return finalContext;
    }
    
    public void setFinalContext(@NotNull String context) {
        this.finalContext = context;
    }
    
    @NotNull
    public String getFinalPrompt() {
        return finalPrompt;
    }
    
    public void setFinalPrompt(@NotNull String prompt) {
        this.finalPrompt = prompt;
    }
    
    @NotNull
    public List<SourceChunk> getAllSources() {
        return allSources;
    }
    
    public void setAllSources(@NotNull List<SourceChunk> sources) {
        this.allSources = new ArrayList<>(sources);
    }
    
    // === Custom attributes ===
    
    /**
     * Sets a custom attribute on the context.
     * 
     * @param key The attribute key
     * @param value The attribute value
     */
    public void setAttribute(@NotNull String key, @Nullable Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }
    
    /**
     * Gets a custom attribute from the context.
     * 
     * @param key The attribute key
     * @param type The expected type
     * @return The attribute value, or null if not set
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getAttribute(@NotNull String key, @NotNull Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException(
                    "Attribute '" + key + "' is of type " + value.getClass().getName() +
                    ", not " + type.getName());
        }
        return (T) value;
    }
    
    /**
     * Checks if a custom attribute exists.
     */
    public boolean hasAttribute(@NotNull String key) {
        return attributes.containsKey(key);
    }
    
    // === Utility methods ===
    
    /**
     * Returns true if any candidates have been found.
     */
    public boolean hasCandidates() {
        return !chunkCandidates.isEmpty() || 
               !entityCandidates.isEmpty() || 
               !relationCandidates.isEmpty();
    }
    
    /**
     * Returns true if context has been built.
     */
    public boolean hasContext() {
        return !finalContext.isEmpty();
    }
    
    /**
     * Gets total candidate count across all types.
     */
    public int getTotalCandidateCount() {
        return chunkCandidates.size() + entityCandidates.size() + relationCandidates.size();
    }
    
    @Override
    public String toString() {
        return "PipelineContext{" +
                "query='" + (query.length() > 50 ? query.substring(0, 50) + "..." : query) + '\'' +
                ", mode=" + param.getMode() +
                ", projectId='" + param.getProjectId() + '\'' +
                ", chunks=" + chunkCandidates.size() +
                ", entities=" + entityCandidates.size() +
                ", relations=" + relationCandidates.size() +
                ", totalTokens=" + totalTokens +
                '}';
    }
}
