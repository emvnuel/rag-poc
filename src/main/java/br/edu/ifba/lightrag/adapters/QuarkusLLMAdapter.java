package br.edu.ifba.lightrag.adapters;

import br.edu.ifba.chat.ChatMessage;
import br.edu.ifba.chat.LlmChatClient;
import br.edu.ifba.chat.LlmChatRequest;
import br.edu.ifba.chat.LlmChatResponse;
import br.edu.ifba.lightrag.llm.LLMFunction;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Adapter that bridges the existing Quarkus LlmChatClient to LightRAG's LLMFunction interface.
 * This allows LightRAG to use the Quarkus-managed LLM client for all chat completions.
 * Uses custom ThreadFactory to ensure virtual threads have proper Quarkus classloader context.
 */
@ApplicationScoped
public class QuarkusLLMAdapter implements LLMFunction {

    private static final Logger LOG = Logger.getLogger(QuarkusLLMAdapter.class);
    private static final ClassLoader QUARKUS_CLASSLOADER = QuarkusLLMAdapter.class.getClassLoader();
    
    // Custom ThreadFactory that sets the Quarkus classloader on each thread
    private static final ThreadFactory THREAD_FACTORY = task -> {
        return Thread.ofVirtual().factory().newThread(() -> {
            Thread.currentThread().setContextClassLoader(QUARKUS_CLASSLOADER);
            task.run();
        });
    };
    
    private static final Executor EXECUTOR = Executors.newThreadPerTaskExecutor(THREAD_FACTORY);

    @Inject
    @RestClient
    LlmChatClient chatClient;

    @ConfigProperty(name = "chat.model")
    String defaultModel;

    @ConfigProperty(name = "chat.temperature", defaultValue = "0.7")
    Double defaultTemperature;

    @ConfigProperty(name = "chat.max.tokens", defaultValue = "2048")
    Integer defaultMaxTokens;

    @ConfigProperty(name = "chat.top.p", defaultValue = "0.9")
    Double defaultTopP;

    @Override
    public CompletableFuture<String> apply(
            @NotNull final String prompt,
            @Nullable final String systemPrompt,
            @Nullable final List<Message> historyMessages,
            @NotNull final Map<String, Object> kwargs) {

        // Execute in a virtual thread with proper Quarkus Arc context
        return CompletableFuture.supplyAsync(() -> {
            final ManagedContext requestContext = Arc.container().requestContext();
            
            if (!requestContext.isActive()) {
                requestContext.activate();
            }
            
            try {
                final int historySize = historyMessages != null ? historyMessages.size() : 0;
                LOG.debugf("LightRAG LLM request - prompt length: %d, system prompt: %s, history size: %d, thread: %s",
                        Integer.valueOf(prompt.length()),
                        systemPrompt != null ? "present" : "none",
                        Integer.valueOf(historySize),
                        Thread.currentThread().getName());

                final List<ChatMessage> messages = buildMessages(prompt, systemPrompt, historyMessages);

                // Extract parameters from kwargs or use defaults
                final String model = (String) kwargs.getOrDefault("model", defaultModel);
                final Double temperature = getDoubleParam(kwargs, "temperature", defaultTemperature);
                final Integer maxTokens = getIntegerParam(kwargs, "max_tokens", defaultMaxTokens);
                final Double topP = getDoubleParam(kwargs, "top_p", defaultTopP);

                final LlmChatRequest request = new LlmChatRequest(
                        model,
                        messages,
                        false, // streaming not supported for LightRAG sync operations
                        maxTokens,
                        temperature,
                        topP
                );

                LOG.debugf("Calling LLM with model: %s, temperature: %.2f, maxTokens: %d",
                        model, Double.valueOf(temperature), Integer.valueOf(maxTokens));

                final LlmChatResponse response = chatClient.chat(request);

                if (response.choices() == null || response.choices().isEmpty()) {
                    throw new RuntimeException("LLM returned no choices in response");
                }

                final String content = response.choices().get(0).message().content();

                final String tokenInfo = response.usage() != null ? String.valueOf(response.usage().totalTokens()) : "unknown";
                LOG.debugf("LLM response received - length: %d characters, tokens: %s",
                        Integer.valueOf(content.length()), tokenInfo);

                return content;

            } catch (Exception e) {
                LOG.errorf(e, "Error calling LLM via QuarkusLLMAdapter");
                throw new RuntimeException("Failed to get LLM completion: " + e.getMessage(), e);
            } finally {
                requestContext.deactivate();
            }
        }, EXECUTOR);
    }

    /**
     * Converts LightRAG messages to Quarkus ChatMessage format.
     * Message order: [system], [history...], [user prompt]
     */
    private List<ChatMessage> buildMessages(
            @NotNull final String prompt,
            @Nullable final String systemPrompt,
            @Nullable final List<Message> historyMessages) {

        final List<ChatMessage> messages = new ArrayList<>();

        // Add system prompt if provided
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new ChatMessage("system", systemPrompt));
        }

        // Add conversation history
        if (historyMessages != null) {
            for (final Message msg : historyMessages) {
                messages.add(new ChatMessage(
                        convertRole(msg.role()),
                        msg.content()
                ));
            }
        }

        // Add user prompt
        messages.add(new ChatMessage("user", prompt));

        return messages;
    }

    /**
     * Converts LightRAG Message.Role enum to string role format expected by chat API.
     */
    private String convertRole(@NotNull final Message.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    /**
     * Safely extracts Double parameter from kwargs map.
     */
    private Double getDoubleParam(final Map<String, Object> kwargs, final String key, final Double defaultValue) {
        final Object value = kwargs.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Safely extracts Integer parameter from kwargs map.
     */
    private Integer getIntegerParam(final Map<String, Object> kwargs, final String key, final Integer defaultValue) {
        final Object value = kwargs.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
}
