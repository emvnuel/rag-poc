package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Result of a LightRAG query that supports streaming responses.
 * 
 * <p>Ported from official LightRAG Python's QueryResult with is_streaming support.
 * This allows query executors to return either a complete response or a streaming
 * response for better UX on long answers.</p>
 * 
 * <h2>Usage for Complete Response:</h2>
 * <pre>{@code
 * StreamingQueryResult result = StreamingQueryResult.complete(answer, sources, mode, count);
 * if (!result.isStreaming()) {
 *     String answer = result.answer();
 * }
 * }</pre>
 * 
 * <h2>Usage for Streaming Response:</h2>
 * <pre>{@code
 * StreamingQueryResult result = StreamingQueryResult.streaming(publisher, sources, mode, count);
 * if (result.isStreaming()) {
 *     result.responseStream().subscribe(subscriber);
 * }
 * }</pre>
 * 
 * @since spec-008 (streaming response enhancement)
 */
public final class StreamingQueryResult {
    
    private final String answer;
    private final Flow.Publisher<String> responseStream;
    private final List<LightRAGQueryResult.SourceChunk> sourceChunks;
    private final QueryParam.Mode mode;
    private final int totalSources;
    private final boolean streaming;
    
    /**
     * Private constructor - use factory methods.
     */
    private StreamingQueryResult(
        @Nullable String answer,
        @Nullable Flow.Publisher<String> responseStream,
        @NotNull List<LightRAGQueryResult.SourceChunk> sourceChunks,
        @NotNull QueryParam.Mode mode,
        int totalSources,
        boolean streaming
    ) {
        this.answer = answer;
        this.responseStream = responseStream;
        this.sourceChunks = Objects.requireNonNull(sourceChunks);
        this.mode = Objects.requireNonNull(mode);
        this.totalSources = totalSources;
        this.streaming = streaming;
    }
    
    /**
     * Creates a complete (non-streaming) query result.
     * 
     * @param answer the complete answer text
     * @param sourceChunks the source chunks used
     * @param mode the query mode
     * @param totalSources total number of sources
     * @return StreamingQueryResult with complete answer
     */
    public static StreamingQueryResult complete(
        @NotNull String answer,
        @NotNull List<LightRAGQueryResult.SourceChunk> sourceChunks,
        @NotNull QueryParam.Mode mode,
        int totalSources
    ) {
        return new StreamingQueryResult(
            Objects.requireNonNull(answer),
            null,
            sourceChunks,
            mode,
            totalSources,
            false
        );
    }
    
    /**
     * Creates a streaming query result.
     * 
     * @param responseStream the publisher that emits response chunks
     * @param sourceChunks the source chunks used
     * @param mode the query mode
     * @param totalSources total number of sources
     * @return StreamingQueryResult with streaming response
     */
    public static StreamingQueryResult streaming(
        @NotNull Flow.Publisher<String> responseStream,
        @NotNull List<LightRAGQueryResult.SourceChunk> sourceChunks,
        @NotNull QueryParam.Mode mode,
        int totalSources
    ) {
        return new StreamingQueryResult(
            null,
            Objects.requireNonNull(responseStream),
            sourceChunks,
            mode,
            totalSources,
            true
        );
    }
    
    /**
     * Creates from a standard LightRAGQueryResult.
     * 
     * @param result the standard query result
     * @return StreamingQueryResult wrapping the result
     */
    public static StreamingQueryResult fromQueryResult(@NotNull LightRAGQueryResult result) {
        return complete(
            result.answer(),
            result.sourceChunks(),
            result.mode(),
            result.totalSources()
        );
    }
    
    /**
     * Converts to a standard LightRAGQueryResult.
     * 
     * <p>If this is a streaming result, this will throw an exception.
     * Use {@link #collectStream()} to first collect the stream.</p>
     * 
     * @return LightRAGQueryResult
     * @throws IllegalStateException if this is a streaming result
     */
    public LightRAGQueryResult toQueryResult() {
        if (streaming) {
            throw new IllegalStateException(
                "Cannot convert streaming result to LightRAGQueryResult. " +
                "Use collectStream() first to collect the stream."
            );
        }
        return new LightRAGQueryResult(answer, sourceChunks, mode, totalSources);
    }
    
    /**
     * Checks if this is a streaming response.
     * 
     * @return true if streaming, false if complete
     */
    public boolean isStreaming() {
        return streaming;
    }
    
    /**
     * Gets the complete answer text.
     * 
     * @return the answer, or null if streaming
     */
    @Nullable
    public String answer() {
        return answer;
    }
    
    /**
     * Gets the response stream for streaming responses.
     * 
     * @return the publisher, or null if not streaming
     */
    @Nullable
    public Flow.Publisher<String> responseStream() {
        return responseStream;
    }
    
    /**
     * Gets the source chunks used to generate the response.
     * 
     * @return list of source chunks
     */
    @NotNull
    public List<LightRAGQueryResult.SourceChunk> sourceChunks() {
        return sourceChunks;
    }
    
    /**
     * Gets the query mode used.
     * 
     * @return the query mode
     */
    @NotNull
    public QueryParam.Mode mode() {
        return mode;
    }
    
    /**
     * Gets the total number of sources.
     * 
     * @return source count
     */
    public int totalSources() {
        return totalSources;
    }
    
    /**
     * Collects a streaming response into a complete response.
     * 
     * <p>Blocks until the stream completes and returns a new StreamingQueryResult
     * with the collected answer text.</p>
     * 
     * @return StreamingQueryResult with complete answer
     * @throws IllegalStateException if this is not a streaming result
     */
    public StreamingQueryResult collectStream() {
        if (!streaming || responseStream == null) {
            return this;
        }
        
        // Create a simple collector subscriber
        StringBuilder collected = new StringBuilder();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        
        responseStream.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }
            
            @Override
            public void onNext(String item) {
                collected.append(item);
            }
            
            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                latch.countDown();
            }
            
            @Override
            public void onComplete() {
                latch.countDown();
            }
        });
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while collecting stream", e);
        }
        
        if (error.get() != null) {
            throw new RuntimeException("Error collecting stream", error.get());
        }
        
        return complete(collected.toString(), sourceChunks, mode, totalSources);
    }
}
