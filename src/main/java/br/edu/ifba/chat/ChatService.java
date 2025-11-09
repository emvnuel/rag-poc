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

    @ConfigProperty(name = "chat.max.results")
    Integer maxResults;

    public ChatResponse chat(final ChatRequest request) {
        final UUID projectId = request.projectId();
        final String userMessage = request.message();
        final List<ChatMessage> history = request.history() != null ? request.history() : new ArrayList<>();

        LOG.infof("Processing chat request for project: %s, message: '%s'", projectId, userMessage);

        final SearchResponse searchResponse = searchService.search(userMessage, projectId, maxResults);
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

        final List<ChatMessage> updatedMessages = new ArrayList<>(history);
        updatedMessages.add(new ChatMessage("user", userMessage));
        updatedMessages.add(assistantMessage);

        return new ChatResponse(
            assistantMessage.content(),
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
        for (int i = 0; i < sources.size(); i++) {
            final SearchResult source = sources.get(i);
            // Skip "LightRAG Answer" and count only sources with document IDs
            if (i == 0 && source.id() == null && "LightRAG Answer".equals(source.source())) {
                continue;
            }
            if (source.id() != null) {
                citableSources++;
            }
        }

        // If no citable sources exist, use the no-context prompt
        if (citableSources == 0) {
            LOG.infof("No citable sources available, using no-context prompt");
            return systemPromptNoContext;
        }

        final StringBuilder context = new StringBuilder();
        context.append(systemPromptWithContext);
        context.append("\n\n");
        
        // Add citation instruction - ONLY when citable sources exist
        context.append("IMPORTANTE: Ao responder, você DEVE citar as fontes usando o UUID do documento entre colchetes. ");
        context.append("Exemplo: [a1b2c3d4-5678-9abc-def0-123456789abc]. ");
        context.append("NUNCA invente UUIDs. Use APENAS os UUIDs exatos fornecidos abaixo nas fontes disponíveis. ");
        context.append("NÃO copie UUIDs de outras mensagens ou exemplos. ");
        context.append("Se não houver UUID listado abaixo para uma informação, NÃO cite.\n\n");
        context.append(String.format("Fontes disponíveis (%d documento(s)):\n\n", citableSources));

        // Format sources with UUID citations
        // Note: All sources (except index 0 "LightRAG Answer") are guaranteed to have document IDs
        // because SearchService filters out sources without document IDs
        for (int i = 0; i < sources.size(); i++) {
            final SearchResult source = sources.get(i);
            
            // First source is usually the LightRAG answer (skip adding it as a source reference)
            if (i == 0 && source.id() == null && "LightRAG Answer".equals(source.source())) {
                // Skip the synthesized answer in the context - it will be in the response
                continue;
            }
            
            // Add source with UUID citation
            // All sources here should have document IDs due to filtering in SearchService
            if (source.id() != null) {
                context.append(String.format("[%s] ", source.id()));
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
