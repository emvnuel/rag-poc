package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.utils.TokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for query mode enhancements.
 * 
 * <p>Tests verify keyword extraction parsing and context merging
 * using round-robin interleaving with token budget enforcement.</p>
 */
class QueryModeEnhancementsTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(QueryModeEnhancementsTest.class);
    
    private ContextMerger contextMerger;
    
    @BeforeEach
    void setUp() {
        LOG.info("Setting up QueryModeEnhancementsTest");
        contextMerger = new ContextMerger();
    }
    
    // =========================================================================
    // KeywordResult Tests
    // =========================================================================
    
    /**
     * Test KeywordResult creation with fresh keywords.
     */
    @Test
    void testKeywordResultCreation() {
        LOG.info("Testing KeywordResult creation");
        
        List<String> highLevel = List.of("research trends", "technology");
        List<String> lowLevel = List.of("OpenAI", "machine learning");
        String queryHash = "abc123";
        
        KeywordResult result = KeywordResult.fresh(highLevel, lowLevel, queryHash);
        
        assertEquals(highLevel, result.highLevelKeywords());
        assertEquals(lowLevel, result.lowLevelKeywords());
        assertEquals(queryHash, result.queryHash());
        assertNull(result.cachedAt(), "Fresh result should not have cachedAt");
        
        LOG.info("KeywordResult creation test passed");
    }
    
    /**
     * Test KeywordResult cached version.
     */
    @Test
    void testKeywordResultCached() {
        LOG.info("Testing KeywordResult cached version");
        
        List<String> highLevel = List.of("theme");
        List<String> lowLevel = List.of("entity");
        String queryHash = "hash123";
        
        KeywordResult cached = KeywordResult.cached(highLevel, lowLevel, queryHash);
        
        assertNotNull(cached.cachedAt(), "Cached result should have timestamp");
        assertEquals(highLevel, cached.highLevelKeywords());
        assertEquals(lowLevel, cached.lowLevelKeywords());
        
        LOG.info("KeywordResult cached test passed");
    }
    
    /**
     * Test empty KeywordResult.
     */
    @Test
    void testKeywordResultEmpty() {
        LOG.info("Testing empty KeywordResult");
        
        KeywordResult empty = KeywordResult.empty("emptyhash");
        
        assertTrue(empty.highLevelKeywords().isEmpty());
        assertTrue(empty.lowLevelKeywords().isEmpty());
        assertTrue(empty.isEmpty());
        assertEquals("emptyhash", empty.queryHash());
        
        LOG.info("Empty KeywordResult test passed");
    }
    
    // =========================================================================
    // ContextItem Tests
    // =========================================================================
    
    /**
     * Test ContextItem factory methods.
     */
    @Test
    void testContextItemFactories() {
        LOG.info("Testing ContextItem factory methods");
        
        // Entity
        ContextItem entity = ContextItem.entity("Entity Name", "Entity content", 10);
        assertEquals("entity", entity.type());
        assertEquals("Entity Name", entity.sourceId());
        assertTrue(entity.isEntity());
        assertFalse(entity.isRelation());
        assertFalse(entity.isChunk());
        
        // Relation
        ContextItem relation = ContextItem.relation("A", "B", "A -> B", 5);
        assertEquals("relation", relation.type());
        assertEquals("A->B", relation.sourceId());
        assertTrue(relation.isRelation());
        
        // Chunk
        ContextItem chunk = ContextItem.chunk("chunk-1", "Chunk content", "/path/file.txt", 15);
        assertEquals("chunk", chunk.type());
        assertEquals("chunk-1", chunk.sourceId());
        assertEquals("/path/file.txt", chunk.filePath());
        assertTrue(chunk.isChunk());
        
        LOG.info("ContextItem factory methods test passed");
    }
    
    // =========================================================================
    // ContextMerger Tests
    // =========================================================================
    
    /**
     * Test round-robin interleaving with two sources.
     */
    @Test
    void testRoundRobinInterleavingTwoSources() {
        LOG.info("Testing round-robin interleaving with two sources");
        
        // Source 1: Entities
        List<ContextItem> entities = List.of(
            ContextItem.entity("E1", "Entity 1 content", 10),
            ContextItem.entity("E2", "Entity 2 content", 10),
            ContextItem.entity("E3", "Entity 3 content", 10)
        );
        
        // Source 2: Chunks
        List<ContextItem> chunks = List.of(
            ContextItem.chunk("C1", "Chunk 1 content", null, 10),
            ContextItem.chunk("C2", "Chunk 2 content", null, 10)
        );
        
        // Large budget to include all
        MergeResult result = contextMerger.mergeWithMetadata(List.of(entities, chunks), 10000);
        
        // Should interleave: E1, C1, E2, C2, E3
        assertEquals(5, result.itemsIncluded(), "Should include all 5 items");
        assertEquals(0, result.itemsTruncated(), "No items should be truncated");
        
        // Verify interleaving order
        List<ContextItem> items = result.includedItems();
        assertEquals("E1", items.get(0).sourceId(), "First item should be E1");
        assertEquals("C1", items.get(1).sourceId(), "Second item should be C1");
        assertEquals("E2", items.get(2).sourceId(), "Third item should be E2");
        assertEquals("C2", items.get(3).sourceId(), "Fourth item should be C2");
        assertEquals("E3", items.get(4).sourceId(), "Fifth item should be E3");
        
        LOG.info("Round-robin interleaving two sources test passed");
    }
    
    /**
     * Test round-robin with three sources.
     */
    @Test
    void testRoundRobinInterleavingThreeSources() {
        LOG.info("Testing round-robin interleaving with three sources");
        
        List<ContextItem> entities = List.of(
            ContextItem.entity("E1", "Entity", 10),
            ContextItem.entity("E2", "Entity", 10)
        );
        
        List<ContextItem> relations = List.of(
            ContextItem.relation("A", "B", "A->B", 10)
        );
        
        List<ContextItem> chunks = List.of(
            ContextItem.chunk("C1", "Chunk", null, 10),
            ContextItem.chunk("C2", "Chunk", null, 10),
            ContextItem.chunk("C3", "Chunk", null, 10)
        );
        
        MergeResult result = contextMerger.mergeWithMetadata(
            List.of(entities, relations, chunks), 10000);
        
        // Should interleave: E1, R1, C1, E2, C2, C3
        assertEquals(6, result.itemsIncluded());
        
        // Verify order
        List<ContextItem> items = result.includedItems();
        assertEquals("E1", items.get(0).sourceId());
        assertEquals("A->B", items.get(1).sourceId());
        assertEquals("C1", items.get(2).sourceId());
        assertEquals("E2", items.get(3).sourceId());
        // C2 should be next (relations exhausted)
        assertEquals("C2", items.get(4).sourceId());
        assertEquals("C3", items.get(5).sourceId());
        
        LOG.info("Round-robin interleaving three sources test passed");
    }
    
    /**
     * Test token budget enforcement.
     */
    @Test
    void testTokenBudgetEnforcement() {
        LOG.info("Testing token budget enforcement");
        
        // Create items with known token counts
        List<ContextItem> items = List.of(
            ContextItem.entity("E1", "Short", 10),
            ContextItem.entity("E2", "Medium content here", 20),
            ContextItem.entity("E3", "This is a longer content that takes more tokens", 50)
        );
        
        // Budget allows only first two items
        MergeResult result = contextMerger.mergeWithMetadata(List.of(items), 35);
        
        // Should include E1 and E2 (10 + separator + 20 = ~32 tokens)
        assertEquals(2, result.itemsIncluded(), "Should include 2 items within budget");
        assertEquals(1, result.itemsTruncated(), "Should truncate 1 item");
        assertTrue(result.totalTokens() <= 35, "Total tokens should not exceed budget");
        
        LOG.info("Token budget enforcement test passed");
    }
    
    /**
     * Test empty sources handling.
     */
    @Test
    void testEmptySourcesHandling() {
        LOG.info("Testing empty sources handling");
        
        // Mix of empty and non-empty sources
        List<ContextItem> empty = List.of();
        List<ContextItem> items = List.of(
            ContextItem.entity("E1", "Content", 10)
        );
        
        MergeResult result = contextMerger.mergeWithMetadata(List.of(empty, items, empty), 1000);
        
        assertEquals(1, result.itemsIncluded());
        assertEquals("E1", result.includedItems().get(0).sourceId());
        
        LOG.info("Empty sources handling test passed");
    }
    
    /**
     * Test all empty sources.
     */
    @Test
    void testAllEmptySources() {
        LOG.info("Testing all empty sources");
        
        MergeResult result = contextMerger.mergeWithMetadata(List.of(), 1000);
        
        assertTrue(result.includedItems().isEmpty());
        assertEquals(0, result.itemsIncluded());
        assertEquals(0, result.totalTokens());
        assertTrue(result.mergedContext().isEmpty());
        
        LOG.info("All empty sources test passed");
    }
    
    /**
     * Test zero budget.
     */
    @Test
    void testZeroBudget() {
        LOG.info("Testing zero budget");
        
        List<ContextItem> items = List.of(
            ContextItem.entity("E1", "Content", 10)
        );
        
        MergeResult result = contextMerger.mergeWithMetadata(List.of(items), 0);
        
        assertTrue(result.includedItems().isEmpty());
        assertEquals(0, result.itemsIncluded());
        
        LOG.info("Zero budget test passed");
    }
    
    /**
     * Test convenience method for two sources.
     */
    @Test
    void testMergeTwoSourcesConvenience() {
        LOG.info("Testing mergeTwoSources convenience method");
        
        List<ContextItem> source1 = List.of(ContextItem.entity("E1", "Entity", 5));
        List<ContextItem> source2 = List.of(ContextItem.chunk("C1", "Chunk", null, 5));
        
        String merged = contextMerger.mergeTwoSources(source1, source2, 1000);
        
        assertTrue(merged.contains("Entity"));
        assertTrue(merged.contains("Chunk"));
        
        LOG.info("mergeTwoSources convenience method test passed");
    }
    
    /**
     * Test convenience method for three sources.
     */
    @Test
    void testMergeThreeSourcesConvenience() {
        LOG.info("Testing mergeThreeSources convenience method");
        
        List<ContextItem> entities = List.of(ContextItem.entity("E1", "Entity content", 5));
        List<ContextItem> relations = List.of(ContextItem.relation("A", "B", "Relation content", 5));
        List<ContextItem> chunks = List.of(ContextItem.chunk("C1", "Chunk content", null, 5));
        
        String merged = contextMerger.mergeThreeSources(entities, relations, chunks, 1000);
        
        assertTrue(merged.contains("Entity content"));
        assertTrue(merged.contains("Relation content"));
        assertTrue(merged.contains("Chunk content"));
        
        LOG.info("mergeThreeSources convenience method test passed");
    }
    
    /**
     * Test capacity estimation.
     */
    @Test
    void testEstimateCapacity() {
        LOG.info("Testing capacity estimation");
        
        List<ContextItem> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(ContextItem.entity("E" + i, "Content item " + i, 10));
        }
        
        // Budget for approximately 5 items (10 tokens each + separators)
        int capacity = contextMerger.estimateCapacity(items, 60);
        
        assertTrue(capacity >= 4 && capacity <= 6, 
            "Should estimate 4-6 items for budget of 60 tokens (got " + capacity + ")");
        
        LOG.info("Capacity estimation test passed");
    }
    
    // =========================================================================
    // TokenUtil Tests
    // =========================================================================
    
    /**
     * Test token estimation.
     */
    @Test
    void testTokenEstimation() {
        LOG.info("Testing token estimation");
        
        // ~4 chars per token approximation
        assertEquals(0, TokenUtil.estimateTokens(""));
        assertTrue(TokenUtil.estimateTokens("test") <= 2); // 4 chars = ~1 token
        assertTrue(TokenUtil.estimateTokens("Hello World") <= 5); // 11 chars = ~3 tokens
        
        LOG.info("Token estimation test passed");
    }
    
    /**
     * Test budget allocation from defaults.
     */
    @Test
    void testBudgetAllocationDefaults() {
        LOG.info("Testing budget allocation defaults");
        
        TokenUtil.BudgetAllocation allocation = TokenUtil.BudgetAllocation.withDefaults(4000);
        
        assertEquals(4000, allocation.totalBudget());
        assertEquals(1600, allocation.entityBudget()); // 40%
        assertEquals(1200, allocation.relationBudget()); // 30%
        assertEquals(1200, allocation.chunkBudget()); // 30%
        
        // Verify ratios sum to total (within rounding)
        int sum = allocation.entityBudget() + allocation.relationBudget() + allocation.chunkBudget();
        assertTrue(Math.abs(sum - 4000) <= 10, "Budgets should sum to total within rounding");
        
        LOG.info("Budget allocation defaults test passed");
    }
    
    /**
     * Test truncation to token limit.
     */
    @Test
    void testTruncateToTokenLimit() {
        LOG.info("Testing truncation to token limit");
        
        String longText = "This is a very long text that should be truncated to fit within the token limit.";
        
        // Truncate to 5 tokens (~20 chars)
        String truncated = TokenUtil.truncateToTokenLimit(longText, 5);
        
        assertTrue(truncated.length() < longText.length(), "Should be truncated");
        assertTrue(truncated.endsWith("..."), "Should end with ellipsis");
        
        // Short text should not be truncated
        String shortText = "Hi";
        String notTruncated = TokenUtil.truncateToTokenLimit(shortText, 100);
        assertEquals(shortText, notTruncated, "Short text should not be truncated");
        
        LOG.info("Truncation to token limit test passed");
    }
    
    /**
     * Test would exceed budget check.
     */
    @Test
    void testWouldExceedBudget() {
        LOG.info("Testing would exceed budget check");
        
        // "short" = 5 chars -> ~2 tokens, 10 + 2 = 12 <= 100 -> false
        assertFalse(TokenUtil.wouldExceedBudget(10, "short", 100));
        // Need > 5 tokens (>20 chars) to exceed budget of 100 when at 95
        // "this text will exceed the budget" = 33 chars -> ~9 tokens, 95 + 9 = 104 > 100 -> true
        assertTrue(TokenUtil.wouldExceedBudget(95, "this text will exceed the budget", 100));
        
        LOG.info("Would exceed budget test passed");
    }
    
    /**
     * Test safe token estimation with null.
     */
    @Test
    void testEstimateTokensSafe() {
        LOG.info("Testing safe token estimation");
        
        assertEquals(0, TokenUtil.estimateTokensSafe(null));
        assertEquals(0, TokenUtil.estimateTokensSafe(""));
        assertTrue(TokenUtil.estimateTokensSafe("text") > 0);
        
        LOG.info("Safe token estimation test passed");
    }
}
