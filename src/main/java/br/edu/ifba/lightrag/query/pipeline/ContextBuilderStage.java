package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.QueryParam.ConversationMessage;
import br.edu.ifba.lightrag.query.ContextItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline stage that builds the final prompt from merged context.
 * 
 * <p>This stage takes the merged context items and constructs the final prompt
 * that will be sent to the LLM. It handles:</p>
 * <ul>
 *   <li>Section headers for different context types</li>
 *   <li>Conversation history formatting</li>
 *   <li>User prompt/query integration</li>
 *   <li>Response type instructions</li>
 * </ul>
 * 
 * <h2>Prompt Structure:</h2>
 * <pre>
 * ## Conversation History
 * [Previous messages if any]
 * 
 * ## Context
 * ### Entities
 * [Entity content]
 * ### Relations
 * [Relation content]
 * ### Sources
 * [Chunk content]
 * 
 * ## Query
 * [User's question]
 * 
 * Please respond with: [response type]
 * </pre>
 * 
 * @since spec-008
 */
public class ContextBuilderStage implements PipelineStage {
    
    private static final Logger logger = LoggerFactory.getLogger(ContextBuilderStage.class);
    private static final String STAGE_NAME = "context-builder";
    
    // Default section headers
    private static final String HEADER_CONVERSATION = "## Conversation History";
    private static final String HEADER_CONTEXT = "## Context";
    private static final String HEADER_ENTITIES = "### Entities";
    private static final String HEADER_RELATIONS = "### Relations";
    private static final String HEADER_SOURCES = "### Sources";
    private static final String HEADER_QUERY = "## Query";
    
    private final boolean includeHeaders;
    private final boolean groupByType;
    @Nullable
    private final String customSystemPrompt;
    
    /**
     * Creates a ContextBuilderStage with default settings.
     * 
     * <p>Uses headers and groups content by type.</p>
     */
    public ContextBuilderStage() {
        this(true, true, null);
    }
    
    /**
     * Creates a ContextBuilderStage with custom system prompt.
     * 
     * @param customSystemPrompt optional system prompt to prepend
     */
    public ContextBuilderStage(@Nullable String customSystemPrompt) {
        this(true, true, customSystemPrompt);
    }
    
    /**
     * Creates a ContextBuilderStage with full customization.
     * 
     * @param includeHeaders whether to include section headers
     * @param groupByType whether to group content by type (entity/relation/chunk)
     * @param customSystemPrompt optional system prompt to prepend
     */
    public ContextBuilderStage(boolean includeHeaders, boolean groupByType, 
                                @Nullable String customSystemPrompt) {
        this.includeHeaders = includeHeaders;
        this.groupByType = groupByType;
        this.customSystemPrompt = customSystemPrompt;
    }
    
    @Override
    public CompletableFuture<PipelineContext> process(@NotNull PipelineContext context) {
        logger.debug("Building final prompt with headers={}, groupByType={}", 
                includeHeaders, groupByType);
        
        StringBuilder prompt = new StringBuilder();
        QueryParam param = context.getParam();
        
        // 1. Add custom system prompt if provided
        if (customSystemPrompt != null && !customSystemPrompt.isEmpty()) {
            prompt.append(customSystemPrompt).append("\n\n");
        }
        
        // 2. Add conversation history if present
        List<ConversationMessage> history = param.getConversationHistory();
        if (!history.isEmpty()) {
            appendConversationHistory(prompt, history);
        }
        
        // 3. Add context section
        if (groupByType) {
            appendGroupedContext(prompt, context);
        } else {
            appendFlatContext(prompt, context);
        }
        
        // 4. Add query section
        appendQuery(prompt, context.getQuery(), param);
        
        // Store final prompt
        String finalPrompt = prompt.toString().trim();
        context.setFinalPrompt(finalPrompt);
        
        logger.debug("Built prompt with {} characters", finalPrompt.length());
        
        return CompletableFuture.completedFuture(context);
    }
    
    /**
     * Appends conversation history to the prompt.
     */
    private void appendConversationHistory(StringBuilder prompt, 
                                            List<ConversationMessage> history) {
        if (includeHeaders) {
            prompt.append(HEADER_CONVERSATION).append("\n\n");
        }
        
        for (ConversationMessage message : history) {
            String roleLabel = formatRole(message.role());
            prompt.append(roleLabel).append(": ").append(message.content()).append("\n\n");
        }
    }
    
    /**
     * Formats a role string for display.
     */
    private String formatRole(String role) {
        if (role == null || role.isEmpty()) {
            return "Unknown";
        }
        // Capitalize first letter
        return Character.toUpperCase(role.charAt(0)) + role.substring(1).toLowerCase();
    }
    
    /**
     * Appends context grouped by type (entities, relations, chunks).
     */
    private void appendGroupedContext(StringBuilder prompt, PipelineContext context) {
        List<ContextItem> merged = context.getMergedItems();
        
        if (merged.isEmpty()) {
            logger.debug("No context items to include in prompt");
            return;
        }
        
        if (includeHeaders) {
            prompt.append(HEADER_CONTEXT).append("\n\n");
        }
        
        // Separate items by type
        List<ContextItem> entities = merged.stream()
                .filter(ContextItem::isEntity)
                .toList();
        List<ContextItem> relations = merged.stream()
                .filter(ContextItem::isRelation)
                .toList();
        List<ContextItem> chunks = merged.stream()
                .filter(ContextItem::isChunk)
                .toList();
        
        // Append each section
        if (!entities.isEmpty()) {
            appendSection(prompt, HEADER_ENTITIES, entities);
        }
        
        if (!relations.isEmpty()) {
            appendSection(prompt, HEADER_RELATIONS, relations);
        }
        
        if (!chunks.isEmpty()) {
            appendSection(prompt, HEADER_SOURCES, chunks);
        }
    }
    
    /**
     * Appends a section of context items.
     */
    private void appendSection(StringBuilder prompt, String header, List<ContextItem> items) {
        if (includeHeaders) {
            prompt.append(header).append("\n");
        }
        
        for (ContextItem item : items) {
            prompt.append("- ").append(item.content()).append("\n");
        }
        prompt.append("\n");
    }
    
    /**
     * Appends context without grouping (preserves round-robin order).
     */
    private void appendFlatContext(StringBuilder prompt, PipelineContext context) {
        List<ContextItem> merged = context.getMergedItems();
        
        if (merged.isEmpty()) {
            logger.debug("No context items to include in prompt");
            return;
        }
        
        if (includeHeaders) {
            prompt.append(HEADER_CONTEXT).append("\n\n");
        }
        
        for (ContextItem item : merged) {
            String typePrefix = getTypePrefix(item.type());
            prompt.append(typePrefix).append(item.content()).append("\n\n");
        }
    }
    
    /**
     * Gets a prefix for a context item type.
     */
    private String getTypePrefix(String type) {
        return switch (type) {
            case "entity" -> "[Entity] ";
            case "relation" -> "[Relation] ";
            case "chunk" -> "[Source] ";
            default -> "";
        };
    }
    
    /**
     * Appends the query section with response type instruction.
     */
    private void appendQuery(StringBuilder prompt, String query, QueryParam param) {
        if (includeHeaders) {
            prompt.append(HEADER_QUERY).append("\n\n");
        }
        
        prompt.append(query).append("\n\n");
        
        // Add response type instruction
        String responseType = param.getResponseType();
        if (responseType != null && !responseType.isEmpty()) {
            prompt.append("Please respond with: ").append(responseType).append("\n");
        }
    }
    
    @Override
    public String getName() {
        return STAGE_NAME;
    }
    
    @Override
    public boolean shouldSkip(@NotNull PipelineContext context) {
        // Never skip - always need to build the final prompt
        // Even with no context, we need to format the query
        return false;
    }
    
    /**
     * Checks if headers are included in the output.
     * 
     * @return true if headers are included
     */
    public boolean isIncludeHeaders() {
        return includeHeaders;
    }
    
    /**
     * Checks if content is grouped by type.
     * 
     * @return true if grouped by type
     */
    public boolean isGroupByType() {
        return groupByType;
    }
    
    /**
     * Gets the custom system prompt.
     * 
     * @return the system prompt, or null if not set
     */
    @Nullable
    public String getCustomSystemPrompt() {
        return customSystemPrompt;
    }
}
