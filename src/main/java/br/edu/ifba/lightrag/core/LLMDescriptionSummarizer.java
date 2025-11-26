package br.edu.ifba.lightrag.core;

import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import br.edu.ifba.lightrag.utils.TokenUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LLM-based implementation of DescriptionSummarizer.
 * 
 * <p>Uses Large Language Model to produce coherent summaries when accumulated
 * entity descriptions exceed the configured token threshold. Results are cached
 * in the ExtractionCache to avoid redundant LLM calls.</p>
 * 
 * <h2>Summarization Strategy:</h2>
 * <ol>
 *   <li>Check if total tokens exceed threshold</li>
 *   <li>If few descriptions (≤10), summarize directly</li>
 *   <li>If many descriptions (>10), use map-reduce pattern</li>
 *   <li>Cache result for future rebuilds</li>
 * </ol>
 * 
 * <h2>Configuration:</h2>
 * <pre>{@code
 * lightrag.description.max-tokens=500
 * lightrag.description.summarization-threshold=300
 * }</pre>
 * 
 * @see DescriptionSummarizer
 * @see LightRAGExtractionConfig.Description
 */
@ApplicationScoped
public class LLMDescriptionSummarizer implements DescriptionSummarizer {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMDescriptionSummarizer.class);
    
    private static final int MAP_REDUCE_THRESHOLD = 10;
    private static final int MAP_REDUCE_BATCH_SIZE = 5;
    
    private static final String SUMMARIZATION_SYSTEM_PROMPT = """
        You are a precise summarization assistant. Your task is to merge multiple descriptions 
        of the same entity into a single, coherent description that captures all key information.
        
        Guidelines:
        - Preserve factual information from all descriptions
        - Remove redundant or duplicate information
        - Maintain a neutral, encyclopedic tone
        - Keep the summary concise but comprehensive
        - Do not add information not present in the source descriptions
        """;
    
    private static final String SUMMARIZATION_USER_PROMPT_TEMPLATE = """
        Entity: %s
        %s
        Descriptions to merge:
        %s
        
        Produce a single, coherent description that captures all key information from the above descriptions.
        Output only the merged description, nothing else.
        """;
    
    private final LLMFunction llmFunction;
    private final ExtractionCacheStorage cacheStorage;
    private final LightRAGExtractionConfig config;
    
    @Inject
    public LLMDescriptionSummarizer(
        LLMFunction llmFunction,
        ExtractionCacheStorage cacheStorage,
        LightRAGExtractionConfig config
    ) {
        this.llmFunction = llmFunction;
        this.cacheStorage = cacheStorage;
        this.config = config;
    }
    
    /**
     * Constructor for manual instantiation (testing, non-CDI contexts).
     */
    public LLMDescriptionSummarizer(
        LLMFunction llmFunction,
        ExtractionCacheStorage cacheStorage,
        int summarizationThreshold,
        int maxTokens
    ) {
        this.llmFunction = llmFunction;
        this.cacheStorage = cacheStorage;
        this.config = new ManualConfig(summarizationThreshold, maxTokens);
    }
    
    @Override
    public boolean needsSummarization(@NotNull List<String> descriptions) {
        if (descriptions.isEmpty() || descriptions.size() == 1) {
            return false;
        }
        int totalTokens = estimateTokenCount(descriptions);
        return totalTokens > getSummarizationThreshold();
    }
    
    @Override
    public CompletableFuture<String> summarize(
        @NotNull String entityName,
        @NotNull List<String> descriptions,
        @NotNull String projectId
    ) {
        return summarize(entityName, null, descriptions, projectId);
    }
    
    @Override
    public CompletableFuture<String> summarize(
        @NotNull String entityName,
        String entityType,
        @NotNull List<String> descriptions,
        @NotNull String projectId
    ) {
        if (descriptions.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        
        if (descriptions.size() == 1) {
            return CompletableFuture.completedFuture(descriptions.get(0));
        }
        
        // Generate content hash for caching
        String contentHash = computeContentHash(entityName, descriptions);
        
        // Check cache first
        return cacheStorage.get(projectId, CacheType.SUMMARIZATION, contentHash)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    logger.debug("Using cached summarization for entity: {}", entityName);
                    return CompletableFuture.completedFuture(cached.get().result());
                }
                
                // Perform summarization
                return performSummarization(entityName, entityType, descriptions, projectId, contentHash);
            });
    }
    
    private CompletableFuture<String> performSummarization(
        String entityName,
        String entityType,
        List<String> descriptions,
        String projectId,
        String contentHash
    ) {
        logger.info("Summarizing {} descriptions for entity: {}", descriptions.size(), entityName);
        
        CompletableFuture<String> summarizationFuture;
        
        if (descriptions.size() <= MAP_REDUCE_THRESHOLD) {
            // Direct summarization for small lists
            summarizationFuture = directSummarize(entityName, entityType, descriptions);
        } else {
            // Map-reduce for large lists
            summarizationFuture = mapReduceSummarize(entityName, entityType, descriptions);
        }
        
        return summarizationFuture.thenCompose(summary -> {
            // Cache the result
            return cacheStorage.store(
                projectId,
                CacheType.SUMMARIZATION,
                null, // No specific chunk ID for summarization
                contentHash,
                summary,
                TokenUtil.estimateTokens(summary)
            ).thenApply(cacheId -> {
                logger.debug("Cached summarization result with ID: {}", cacheId);
                return summary;
            });
        });
    }
    
    private CompletableFuture<String> directSummarize(
        String entityName,
        String entityType,
        List<String> descriptions
    ) {
        String typeContext = entityType != null && !entityType.isBlank() 
            ? "Type: " + entityType 
            : "";
        
        StringBuilder descList = new StringBuilder();
        for (int i = 0; i < descriptions.size(); i++) {
            descList.append(String.format("%d. %s%n", i + 1, descriptions.get(i)));
        }
        
        String prompt = String.format(
            SUMMARIZATION_USER_PROMPT_TEMPLATE,
            entityName,
            typeContext,
            descList.toString()
        );
        
        // Pass operation type for token tracking (T070)
        return llmFunction.apply(prompt, SUMMARIZATION_SYSTEM_PROMPT, null, 
            Map.of("operation_type", TokenUsage.OP_SUMMARIZATION))
            .thenApply(String::trim);
    }
    
    private CompletableFuture<String> mapReduceSummarize(
        String entityName,
        String entityType,
        List<String> descriptions
    ) {
        logger.debug("Using map-reduce summarization for {} descriptions", descriptions.size());
        
        // Map phase: summarize batches in parallel
        List<CompletableFuture<String>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < descriptions.size(); i += MAP_REDUCE_BATCH_SIZE) {
            int end = Math.min(i + MAP_REDUCE_BATCH_SIZE, descriptions.size());
            List<String> batch = descriptions.subList(i, end);
            batchFutures.add(directSummarize(entityName, entityType, batch));
        }
        
        // Reduce phase: combine batch summaries
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                List<String> batchSummaries = batchFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();
                
                // If still too many, recurse
                if (batchSummaries.size() > MAP_REDUCE_THRESHOLD) {
                    return mapReduceSummarize(entityName, entityType, batchSummaries);
                }
                
                // Final reduction
                return directSummarize(entityName, entityType, batchSummaries);
            });
    }
    
    @Override
    public int estimateTokenCount(@NotNull List<String> descriptions) {
        int total = 0;
        for (String desc : descriptions) {
            total += TokenUtil.estimateTokens(desc);
        }
        return total;
    }
    
    @Override
    public int getSummarizationThreshold() {
        return config.description().summarizationThreshold();
    }
    
    /**
     * Computes SHA-256 hash of entity name and descriptions for cache key.
     */
    private String computeContentHash(String entityName, List<String> descriptions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(entityName.getBytes(StandardCharsets.UTF_8));
            for (String desc : descriptions) {
                digest.update(desc.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Manual configuration holder for non-CDI usage.
     * Uses static nested classes to avoid CDI interceptor binding issues.
     */
    private static final class ManualConfig implements LightRAGExtractionConfig {
        private final int threshold;
        private final int maxTokens;
        private final Gleaning gleaningConfig;
        private final Description descriptionConfig;
        private final Query queryConfig;
        private final Entity entityConfig;
        
        ManualConfig(int threshold, int maxTokens) {
            this.threshold = threshold;
            this.maxTokens = maxTokens;
            this.gleaningConfig = new ManualGleaning();
            this.descriptionConfig = new ManualDescription(threshold, maxTokens);
            this.queryConfig = new ManualQuery();
            this.entityConfig = new ManualEntity();
        }
        
        @Override
        public Gleaning gleaning() {
            return gleaningConfig;
        }
        
        @Override
        public Description description() {
            return descriptionConfig;
        }
        
        @Override
        public Query query() {
            return queryConfig;
        }
        
        @Override
        public Entity entity() {
            return entityConfig;
        }
    }
    
    private static final class ManualGleaning implements LightRAGExtractionConfig.Gleaning {
        @Override
        public boolean enabled() {
            return true;
        }
        
        @Override
        public int maxPasses() {
            return 1;
        }
    }
    
    private static final class ManualDescription implements LightRAGExtractionConfig.Description {
        private final int threshold;
        private final int maxTokens;
        
        ManualDescription(int threshold, int maxTokens) {
            this.threshold = threshold;
            this.maxTokens = maxTokens;
        }
        
        @Override
        public int maxTokens() {
            return maxTokens;
        }
        
        @Override
        public int summarizationThreshold() {
            return threshold;
        }
        
        @Override
        public String separator() {
            return " | ";
        }
    }
    
    private static final class ManualQuery implements LightRAGExtractionConfig.Query {
        private final LightRAGExtractionConfig.KeywordExtraction keywordExtractionConfig = new ManualKeywordExtraction();
        private final LightRAGExtractionConfig.Context contextConfig = new ManualContext();
        
        @Override
        public LightRAGExtractionConfig.KeywordExtraction keywordExtraction() {
            return keywordExtractionConfig;
        }
        
        @Override
        public LightRAGExtractionConfig.Context context() {
            return contextConfig;
        }
    }
    
    private static final class ManualKeywordExtraction implements LightRAGExtractionConfig.KeywordExtraction {
        @Override
        public boolean enabled() {
            return true;
        }
        
        @Override
        public int cacheTtl() {
            return 3600;
        }
    }
    
    private static final class ManualContext implements LightRAGExtractionConfig.Context {
        @Override
        public int maxTokens() {
            return 4000;
        }
        
        @Override
        public double entityBudgetRatio() {
            return 0.4;
        }
        
        @Override
        public double relationBudgetRatio() {
            return 0.3;
        }
        
        @Override
        public double chunkBudgetRatio() {
            return 0.3;
        }
    }
    
    private static final class ManualEntity implements LightRAGExtractionConfig.Entity {
        @Override
        public int nameMaxLength() {
            return 500;
        }
        
        @Override
        public int maxSourceIds() {
            return 50;
        }
    }
}
