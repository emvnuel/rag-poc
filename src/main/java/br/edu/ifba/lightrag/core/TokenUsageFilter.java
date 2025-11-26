package br.edu.ifba.lightrag.core;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;

/**
 * JAX-RS filter that adds token usage headers to HTTP responses.
 * 
 * <p>This filter intercepts all responses and adds the following headers
 * when token usage has been tracked during the request:
 * <ul>
 *   <li>X-Token-Input: Total input tokens used</li>
 *   <li>X-Token-Output: Total output tokens used</li>
 *   <li>X-Token-Total: Sum of input and output tokens</li>
 * </ul>
 * 
 * <p>Headers are only added if any tokens were tracked (to avoid noise on
 * non-LLM endpoints).
 * 
 * <p>Example response headers:
 * <pre>
 * X-Token-Input: 1250
 * X-Token-Output: 350
 * X-Token-Total: 1600
 * </pre>
 * 
 * @see TokenTracker
 * @see TokenTrackerImpl
 */
@Provider
public class TokenUsageFilter implements ContainerResponseFilter {
    
    private static final Logger LOG = Logger.getLogger(TokenUsageFilter.class);
    
    /** Header name for input tokens */
    public static final String HEADER_TOKEN_INPUT = "X-Token-Input";
    
    /** Header name for output tokens */
    public static final String HEADER_TOKEN_OUTPUT = "X-Token-Output";
    
    /** Header name for total tokens */
    public static final String HEADER_TOKEN_TOTAL = "X-Token-Total";
    
    @Inject
    TokenTracker tokenTracker;
    
    @Override
    public void filter(ContainerRequestContext requestContext, 
                       ContainerResponseContext responseContext) throws IOException {
        
        if (tokenTracker == null) {
            LOG.trace("TokenTracker not available, skipping header injection");
            return;
        }
        
        int inputTokens = tokenTracker.getTotalInputTokens();
        int outputTokens = tokenTracker.getTotalOutputTokens();
        int totalTokens = inputTokens + outputTokens;
        
        // Only add headers if tokens were actually tracked
        if (totalTokens > 0) {
            responseContext.getHeaders().add(HEADER_TOKEN_INPUT, inputTokens);
            responseContext.getHeaders().add(HEADER_TOKEN_OUTPUT, outputTokens);
            responseContext.getHeaders().add(HEADER_TOKEN_TOTAL, totalTokens);
            
            LOG.debugf("Added token headers to response: input=%d, output=%d, total=%d",
                    inputTokens, outputTokens, totalTokens);
            
            // Log detailed breakdown at trace level
            if (LOG.isTraceEnabled()) {
                TokenSummary summary = tokenTracker.getSummary();
                summary.byOperationType().forEach((op, tokens) ->
                        LOG.tracef("  %s: %d tokens", op, tokens));
            }
        }
    }
}
