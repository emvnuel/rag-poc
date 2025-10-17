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
    private static final int DEFAULT_MAX_RESULTS = 5;

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

        final SearchResponse searchResponse = searchService.search(userMessage, projectId, DEFAULT_MAX_RESULTS);
        final List<SearchResult> sources = searchResponse.results();

        final String contextPrompt = buildContextPrompt(sources);
        
        final List<ChatMessage> messages = buildMessages(contextPrompt, history, userMessage);

        final LlmChatRequest llmRequest = new LlmChatRequest(chatModel, messages, false, maxTokens, temperature, topP);
        
        LOG.infof("Sending request to LLM with %d context sources and %d messages", 
                sources.size(), messages.size());

        final LlmChatResponse llmResponse = chatClient.chat(llmRequest);

        final ChatMessage assistantMessage = llmResponse.choices().get(0).message();
        
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

        final StringBuilder context = new StringBuilder();
        context.append(systemPromptWithContext);

        for (int i = 0; i < sources.size(); i++) {
            final SearchResult source = sources.get(i);
            context.append(String.format("[Document %d: %s]\n", i + 1, source.source()));
            context.append(source.chunkText());
            context.append("\n\n");
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
