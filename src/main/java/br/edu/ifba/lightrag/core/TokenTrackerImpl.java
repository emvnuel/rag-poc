package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.RequestScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;

/**
 * Request-scoped implementation of {@link TokenTracker}.
 * 
 * <p>This implementation is:
 * <ul>
 *   <li>Request-scoped: Each HTTP request gets its own instance</li>
 *   <li>Thread-safe: Uses CopyOnWriteArrayList and AtomicInteger</li>
 *   <li>Lightweight: Minimal overhead for tracking</li>
 * </ul>
 * 
 * <p>Usage example:
 * <pre>{@code
 * @Inject
 * TokenTracker tokenTracker;
 * 
 * // After LLM call
 * tokenTracker.track(TokenUsage.OP_QUERY, "gpt-4", inputTokens, outputTokens);
 * 
 * // At request end
 * TokenSummary summary = tokenTracker.getSummary();
 * }</pre>
 */
@RequestScoped
public class TokenTrackerImpl implements TokenTracker {
    
    private static final Logger LOG = Logger.getLogger(TokenTrackerImpl.class);
    
    private final CopyOnWriteArrayList<TokenUsage> usages = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalInput = new AtomicInteger(0);
    private final AtomicInteger totalOutput = new AtomicInteger(0);
    
    @Override
    public void track(TokenUsage usage) {
        if (usage == null) {
            LOG.warn("Attempted to track null TokenUsage");
            return;
        }
        
        usages.add(usage);
        totalInput.addAndGet(usage.inputTokens());
        totalOutput.addAndGet(usage.outputTokens());
        
        LOG.debugf("Tracked tokens: op=%s model=%s input=%d output=%d total_input=%d total_output=%d",
                usage.operationType(),
                usage.modelName(),
                usage.inputTokens(),
                usage.outputTokens(),
                totalInput.get(),
                totalOutput.get());
    }
    
    @Override
    public TokenSummary getSummary() {
        Map<String, Integer> byOperationType = usages.stream()
                .collect(groupingBy(
                        TokenUsage::operationType,
                        summingInt(TokenUsage::totalTokens)
                ));
        
        return new TokenSummary(
                totalInput.get(),
                totalOutput.get(),
                byOperationType
        );
    }
    
    @Override
    public void reset() {
        usages.clear();
        totalInput.set(0);
        totalOutput.set(0);
        LOG.debug("Token tracker reset");
    }
    
    @Override
    public List<TokenUsage> getUsages() {
        return new ArrayList<>(usages);
    }
    
    @Override
    public int getTotalInputTokens() {
        return totalInput.get();
    }
    
    @Override
    public int getTotalOutputTokens() {
        return totalOutput.get();
    }
    
    /**
     * Logs a detailed breakdown of token usage by operation type.
     * 
     * <p>Useful for debugging and cost analysis.
     */
    public void logBreakdown() {
        TokenSummary summary = getSummary();
        
        LOG.infof("Token usage breakdown - Total: %d (input: %d, output: %d)",
                summary.totalTokens(),
                summary.totalInputTokens(),
                summary.totalOutputTokens());
        
        summary.byOperationType().forEach((op, tokens) ->
                LOG.infof("  %s: %d tokens", op, tokens));
    }
    
    /**
     * Creates a detailed map of token usage for logging or debugging.
     * 
     * @return Map with detailed breakdown
     */
    public Map<String, Object> getDetailedBreakdown() {
        Map<String, Object> breakdown = new HashMap<>();
        breakdown.put("totalInputTokens", totalInput.get());
        breakdown.put("totalOutputTokens", totalOutput.get());
        breakdown.put("totalTokens", totalInput.get() + totalOutput.get());
        breakdown.put("operationCount", usages.size());
        
        // Breakdown by operation type
        Map<String, Map<String, Integer>> byOperation = new HashMap<>();
        for (TokenUsage usage : usages) {
            byOperation.computeIfAbsent(usage.operationType(), k -> new HashMap<>())
                    .merge("count", 1, Integer::sum);
            byOperation.get(usage.operationType())
                    .merge("inputTokens", usage.inputTokens(), Integer::sum);
            byOperation.get(usage.operationType())
                    .merge("outputTokens", usage.outputTokens(), Integer::sum);
        }
        breakdown.put("byOperationType", byOperation);
        
        return breakdown;
    }
    
    /**
     * Logs per-operation breakdown with detailed statistics.
     * 
     * <p>Produces output like:
     * <pre>
     * Token Usage Breakdown:
     *   Total: 5432 tokens (input: 4000, output: 1432)
     *   QUERY: 3 calls, 2500 input, 800 output
     *   EMBEDDING: 10 calls, 1500 input, 0 output
     *   KEYWORD_EXTRACTION: 1 call, 500 input, 200 output
     * </pre>
     * 
     * @since spec-007 (T071)
     */
    public void logPerOperationBreakdown() {
        if (usages.isEmpty()) {
            LOG.info("Token Usage: No operations tracked");
            return;
        }
        
        LOG.infof("Token Usage Breakdown:");
        LOG.infof("  Total: %d tokens (input: %d, output: %d)",
            totalInput.get() + totalOutput.get(),
            totalInput.get(),
            totalOutput.get());
        
        // Group by operation type and calculate stats
        Map<String, OperationStats> byOperation = new HashMap<>();
        for (TokenUsage usage : usages) {
            byOperation.computeIfAbsent(usage.operationType(), k -> new OperationStats())
                .add(usage);
        }
        
        // Log each operation type
        byOperation.forEach((opType, stats) -> {
            String callWord = stats.count == 1 ? "call" : "calls";
            LOG.infof("  %s: %d %s, %d input, %d output",
                opType, stats.count, callWord, stats.inputTokens, stats.outputTokens);
        });
    }
    
    /**
     * Gets a formatted string of per-operation breakdown for HTTP headers or JSON response.
     * 
     * <p>Format: "QUERY:3:2500:800,EMBEDDING:10:1500:0" where each segment is
     * "operation:count:input:output"</p>
     * 
     * @return Formatted breakdown string
     * @since spec-007 (T071)
     */
    public String getPerOperationBreakdownString() {
        if (usages.isEmpty()) {
            return "";
        }
        
        Map<String, OperationStats> byOperation = new HashMap<>();
        for (TokenUsage usage : usages) {
            byOperation.computeIfAbsent(usage.operationType(), k -> new OperationStats())
                .add(usage);
        }
        
        StringBuilder sb = new StringBuilder();
        byOperation.forEach((opType, stats) -> {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(opType)
              .append(":").append(stats.count)
              .append(":").append(stats.inputTokens)
              .append(":").append(stats.outputTokens);
        });
        
        return sb.toString();
    }
    
    /**
     * Helper class for aggregating operation statistics.
     */
    private static class OperationStats {
        int count = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        
        void add(TokenUsage usage) {
            count++;
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
        }
    }
}
