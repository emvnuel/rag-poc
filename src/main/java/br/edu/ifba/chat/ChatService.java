package br.edu.ifba.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.edu.ifba.document.SearchResponse;
import br.edu.ifba.document.SearchResult;
import br.edu.ifba.document.SearchService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ChatService {

    private static final Logger LOG = Logger.getLogger(ChatService.class);

    @Inject
    SearchService searchService;

    @Inject
    @RestClient
    LlmChatClient chatClient;

    @ConfigProperty(name = "chat.model")
    String chatModel;

    @ConfigProperty(name = "chat.temperature")
    Double temperature;

    @ConfigProperty(name = "chat.max.tokens")
    Integer maxTokens;

    @ConfigProperty(name = "chat.top.p")
    Double topP;

    @ConfigProperty(name = "chat.system.prompt")
    String systemPromptWithContext;

    @ConfigProperty(name = "chat.system.prompt.no.context")
    String systemPromptNoContext;

    public ChatResponse chat(final ChatRequest request) {
        final UUID projectId = request.projectId();
        final String userMessage = request.message();
        final List<ChatMessage> history = request.history() != null ? request.history() : new ArrayList<>();

        LOG.infof("Processing chat request for project: %s, message: '%s'", projectId, userMessage);

        final SearchResponse searchResponse = searchService.search(userMessage, projectId);
        final List<SearchResult> sources = searchResponse.results();

        final String contextPrompt = buildContextPrompt(sources);
        
        final List<ChatMessage> messages = buildMessages(contextPrompt, history, userMessage);

        final LlmChatRequest llmRequest = new LlmChatRequest(chatModel, messages, false, maxTokens, temperature, topP);
        
        // Calculate total token estimate for debugging
        int totalChars = messages.stream()
                .mapToInt(msg -> msg.content() != null ? msg.content().length() : 0)
                .sum();
        int estimatedTokens = totalChars / 4; // Rough estimate: 1 token ~= 4 chars
        
        LOG.infof("Sending request to LLM - sources: %d, messages: %d, total_chars: %d, estimated_tokens: %d, max_tokens: %d, total_budget: %d", 
                sources.size(), messages.size(), totalChars, estimatedTokens, maxTokens, estimatedTokens + maxTokens);

        final LlmChatResponse llmResponse = chatClient.chat(llmRequest);

        // Validate response structure
        if (llmResponse == null) {
            LOG.error("LLM returned null response");
            throw new RuntimeException("LLM returned null response");
        }
        
        if (llmResponse.choices() == null || llmResponse.choices().isEmpty()) {
            LOG.errorf("LLM returned invalid response - choices is null or empty. Response: id=%s, model=%s, object=%s", 
                    llmResponse.id(), llmResponse.model(), llmResponse.object());
            throw new RuntimeException("LLM returned no choices in response");
        }

        final ChatMessage assistantMessage = llmResponse.choices().get(0).message();
        
        if (assistantMessage == null || assistantMessage.content() == null) {
            LOG.error("LLM returned null message or content");
            throw new RuntimeException("LLM returned null message or content");
        }
        
        LOG.infof("Received response from LLM: %s", assistantMessage.content());

        // Post-process response: remove invalid citations when no citable sources exist
        // This handles cases where the LLM invents citations despite instructions not to
        String processedContent = assistantMessage.content();
        boolean hasAnyCitableSources = sources.stream()
            .anyMatch(source -> source.id() != null);
        
        if (!hasAnyCitableSources) {
            // Remove all bracketed citations (e.g., [UUID], [e2cd5fc7-...], [sem-uuid-fornecido], [RAG-contexto-001], [Contexto], etc.)
            processedContent = processedContent.replaceAll("\\s*\\[([^\\]]+)\\]", "");
            LOG.infof("Removed invented citations from response (no citable sources available)");
        }

        final ChatMessage finalAssistantMessage = new ChatMessage(assistantMessage.role(), processedContent);
        
        final List<ChatMessage> updatedMessages = new ArrayList<>(history);
        updatedMessages.add(new ChatMessage("user", userMessage));
        updatedMessages.add(finalAssistantMessage);

        return new ChatResponse(
            processedContent,
            updatedMessages,
            sources,
            llmResponse.model(),
            null,
            llmResponse.usage() != null ? Long.valueOf(llmResponse.usage().promptTokens()) : null,
            llmResponse.usage() != null ? Long.valueOf(llmResponse.usage().completionTokens()) : null
        );
    }

    private String buildContextPrompt(final List<SearchResult> sources) {
        if (sources.isEmpty()) {
            return systemPromptNoContext;
        }

        // Count citable sources (exclude "LightRAG Answer" at index 0)
        int citableSources = 0;
        boolean hasLightRAGAnswer = false;
        for (int i = 0; i < sources.size(); i++) {
            final SearchResult source = sources.get(i);
            // Check if first source is LightRAG synthesized answer
            if (i == 0 && source.id() == null && "LightRAG Answer".equals(source.source())) {
                hasLightRAGAnswer = true;
                continue;
            }
            if (source.id() != null) {
                citableSources++;
            }
        }

        // If no citable sources exist:
        // - If we have a LightRAG answer (e.g., from GLOBAL mode with entities), use it as context
        // - Otherwise, use the no-context prompt
        if (citableSources == 0) {
            if (hasLightRAGAnswer) {
                LOG.infof("No citable sources but LightRAG answer available, using it as context");
                // Use the LightRAG answer as the context
                final SearchResult lightRAGAnswer = sources.get(0);
                final StringBuilder context = new StringBuilder();
                context.append(systemPromptWithContext);
                context.append("\n\n");
                context.append("Contexto (baseado no grafo de conhecimento):\n");
                context.append(lightRAGAnswer.chunkText());
                context.append("\n\nResponda com base neste contexto.");
                return context.toString();
            } else {
                LOG.infof("No citable sources available, using no-context prompt");
                return systemPromptNoContext;
            }
        }

        final StringBuilder context = new StringBuilder();
        context.append(systemPromptWithContext);
        context.append("\n\n");
        
        // Add citation instruction - ONLY when citable sources exist
        context.append("IMPORTANTE: Ao responder, você DEVE citar as fontes usando o formato [UUID:chunk-N] entre colchetes. ");
        context.append("Exemplo: [a1b2c3d4-5678-9abc-def0-123456789abc:chunk-5]. ");
        context.append("NUNCA invente IDs de citação. Use APENAS os IDs exatos fornecidos entre colchetes nas fontes abaixo. ");
        context.append("Cada trecho tem seu próprio ID único com número do chunk. ");
        context.append("NÃO copie IDs de outras mensagens ou exemplos. ");
        context.append("Se não houver ID listado para uma informação, NÃO cite.\n\n");
        context.append(String.format("Fontes disponíveis (%d documento(s)):\n\n", citableSources));

        // Format sources with UUID citations
        // Note: All sources (except index 0 "LightRAG Answer") are guaranteed to have document IDs
        // because SearchService filters out sources without document IDs
        for (int i = 0; i < sources.size(); i++) {
            final SearchResult source = sources.get(i);
            
            // First source is usually the LightRAG synthesized answer - include it as helpful context
            if (i == 0 && source.id() == null && "LightRAG Answer".equals(source.source())) {
                context.append("=== RESPOSTA SINTETIZADA (use como referência) ===\n");
                context.append(source.chunkText());
                context.append("\n\n=== DOCUMENTOS ORIGINAIS ===\n\n");
                continue;
            }
            
            // Add source with UUID:chunk citation
            // All sources here should have document IDs due to filtering in SearchService
            if (source.id() != null) {
                // Format citation ID with chunk number for unique identification
                if (source.chunkIndex() != null) {
                    context.append(String.format("[%s:chunk-%d] ", source.id(), source.chunkIndex()));
                } else {
                    // Fallback for sources without chunk index
                    context.append(String.format("[%s] ", source.id()));
                }
                
                context.append(String.format("(Documento: %s", source.id()));
                if (source.chunkIndex() != null) {
                    context.append(String.format(", Trecho %d", source.chunkIndex()));
                }
                context.append(")\n");
                context.append(source.chunkText());
                context.append("\n\n");
            } else {
                // This should not happen anymore due to filtering, but keep as fallback
                LOG.warnf("Unexpected source without document ID in chat context: %s", source.source());
            }
        }

        return context.toString();
    }

    private List<ChatMessage> buildMessages(final String contextPrompt, 
                                           final List<ChatMessage> history,
                                           final String userMessage) {
        final List<ChatMessage> messages = new ArrayList<>();
        
        messages.add(new ChatMessage("system", contextPrompt));
        
        messages.addAll(history);
        
        messages.add(new ChatMessage("user", userMessage));
        
        return messages;
    }
}
