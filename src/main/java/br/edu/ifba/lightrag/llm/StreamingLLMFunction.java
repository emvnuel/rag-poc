package br.edu.ifba.lightrag.llm;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Flow;

/**
 * Extended interface for LLM functions that support streaming responses.
 * 
 * <p>Ported from official LightRAG Python's QueryResult.is_streaming functionality.
 * Allows query executors to return streaming responses for better UX on long answers.</p>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * if (llmFunction instanceof StreamingLLMFunction streamingLlm) {
 *     Flow.Publisher<String> stream = streamingLlm.applyStreaming(prompt, systemPrompt);
 *     // Subscribe to stream for incremental response
 * }
 * }</pre>
 * 
 * @since spec-008 (streaming response enhancement)
 */
public interface StreamingLLMFunction extends LLMFunction {
    
    /**
     * Generate a streaming completion from the LLM.
     * 
     * <p>Returns a Flow.Publisher that emits response chunks as they are received
     * from the LLM provider. Each chunk contains a portion of the response text.</p>
     * 
     * @param prompt The user prompt
     * @param systemPrompt Optional system prompt for context
     * @return Publisher that emits response chunks
     */
    Flow.Publisher<String> applyStreaming(
        @NotNull String prompt,
        @NotNull String systemPrompt
    );
    
    /**
     * Checks if streaming is supported by this implementation.
     * 
     * @return true if streaming is available
     */
    default boolean supportsStreaming() {
        return true;
    }
}
