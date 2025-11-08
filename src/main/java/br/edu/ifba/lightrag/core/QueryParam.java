package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration parameters for query execution in LightRAG.
 */
public final class QueryParam {
    
    /**
     * Query execution mode.
     */
    public enum Mode {
        /**
         * Focuses on context-dependent information.
         */
        LOCAL,
        
        /**
         * Utilizes global knowledge.
         */
        GLOBAL,
        
        /**
         * Combines local and global retrieval methods.
         */
        HYBRID,
        
        /**
         * Performs a basic search without advanced techniques.
         */
        NAIVE,
        
        /**
         * Integrates knowledge graph and vector retrieval.
         */
        MIX,
        
        /**
         * Bypass retrieval and directly query the LLM.
         */
        BYPASS
    }
    
    @NotNull
    private final Mode mode;
    
    private final boolean onlyNeedContext;
    
    private final boolean onlyNeedPrompt;
    
    @NotNull
    private final String responseType;
    
    private final boolean stream;
    
    private final int topK;
    
    private final int chunkTopK;
    
    private final int maxEntityTokens;
    
    private final int maxRelationTokens;
    
    private final int maxTotalTokens;
    
    @NotNull
    private final List<ConversationMessage> conversationHistory;
    
    @Nullable
    private final List<String> ids;
    
    @Nullable
    private final String userPrompt;
    
    private final boolean enableRerank;
    
    private QueryParam(Builder builder) {
        this.mode = builder.mode;
        this.onlyNeedContext = builder.onlyNeedContext;
        this.onlyNeedPrompt = builder.onlyNeedPrompt;
        this.responseType = builder.responseType;
        this.stream = builder.stream;
        this.topK = builder.topK;
        this.chunkTopK = builder.chunkTopK;
        this.maxEntityTokens = builder.maxEntityTokens;
        this.maxRelationTokens = builder.maxRelationTokens;
        this.maxTotalTokens = builder.maxTotalTokens;
        this.conversationHistory = new ArrayList<>(builder.conversationHistory);
        this.ids = builder.ids != null ? new ArrayList<>(builder.ids) : null;
        this.userPrompt = builder.userPrompt;
        this.enableRerank = builder.enableRerank;
    }
    
    @NotNull
    public Mode getMode() {
        return mode;
    }
    
    public boolean isOnlyNeedContext() {
        return onlyNeedContext;
    }
    
    public boolean isOnlyNeedPrompt() {
        return onlyNeedPrompt;
    }
    
    @NotNull
    public String getResponseType() {
        return responseType;
    }
    
    public boolean isStream() {
        return stream;
    }
    
    public int getTopK() {
        return topK;
    }
    
    public int getChunkTopK() {
        return chunkTopK;
    }
    
    public int getMaxEntityTokens() {
        return maxEntityTokens;
    }
    
    public int getMaxRelationTokens() {
        return maxRelationTokens;
    }
    
    public int getMaxTotalTokens() {
        return maxTotalTokens;
    }
    
    @NotNull
    public List<ConversationMessage> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }
    
    @Nullable
    public List<String> getIds() {
        return ids != null ? new ArrayList<>(ids) : null;
    }
    
    @Nullable
    public String getUserPrompt() {
        return userPrompt;
    }
    
    public boolean isEnableRerank() {
        return enableRerank;
    }
    
    /**
     * Represents a conversation message in history.
     */
    public static record ConversationMessage(@NotNull String role, @NotNull String content) {
        public ConversationMessage {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }
    
    /**
     * Builder for QueryParam instances.
     */
    public static class Builder {
        private Mode mode = Mode.GLOBAL;
        private boolean onlyNeedContext = false;
        private boolean onlyNeedPrompt = false;
        private String responseType = "Multiple Paragraphs";
        private boolean stream = false;
        private int topK = 60;
        private int chunkTopK = 20;
        private int maxEntityTokens = 6000;
        private int maxRelationTokens = 8000;
        private int maxTotalTokens = 30000;
        private List<ConversationMessage> conversationHistory = new ArrayList<>();
        private List<String> ids = null;
        private String userPrompt = null;
        private boolean enableRerank = true;
        
        public Builder mode(@NotNull Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode must not be null");
            return this;
        }
        
        public Builder onlyNeedContext(boolean onlyNeedContext) {
            this.onlyNeedContext = onlyNeedContext;
            return this;
        }
        
        public Builder onlyNeedPrompt(boolean onlyNeedPrompt) {
            this.onlyNeedPrompt = onlyNeedPrompt;
            return this;
        }
        
        public Builder responseType(@NotNull String responseType) {
            this.responseType = Objects.requireNonNull(responseType, "responseType must not be null");
            return this;
        }
        
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }
        
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }
        
        public Builder chunkTopK(int chunkTopK) {
            this.chunkTopK = chunkTopK;
            return this;
        }
        
        public Builder maxEntityTokens(int maxEntityTokens) {
            this.maxEntityTokens = maxEntityTokens;
            return this;
        }
        
        public Builder maxRelationTokens(int maxRelationTokens) {
            this.maxRelationTokens = maxRelationTokens;
            return this;
        }
        
        public Builder maxTotalTokens(int maxTotalTokens) {
            this.maxTotalTokens = maxTotalTokens;
            return this;
        }
        
        public Builder conversationHistory(@NotNull List<ConversationMessage> conversationHistory) {
            this.conversationHistory = new ArrayList<>(conversationHistory);
            return this;
        }
        
        public Builder addConversationMessage(@NotNull String role, @NotNull String content) {
            this.conversationHistory.add(new ConversationMessage(role, content));
            return this;
        }
        
        public Builder ids(@Nullable List<String> ids) {
            this.ids = ids != null ? new ArrayList<>(ids) : null;
            return this;
        }
        
        public Builder userPrompt(@Nullable String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }
        
        public Builder enableRerank(boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }
        
        public QueryParam build() {
            return new QueryParam(this);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a default QueryParam with global mode.
     */
    public static QueryParam defaults() {
        return new Builder().build();
    }
    
    /**
     * Creates a QueryParam with the specified mode.
     */
    public static QueryParam withMode(@NotNull Mode mode) {
        return new Builder().mode(mode).build();
    }
}
