package br.edu.ifba.lightrag.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TokenUtil with jtokkit integration.
 * Verifies accurate token counting and text manipulation.
 */
class TokenUtilTest {
    
    @Test
    @DisplayName("Should initialize jtokkit encoding successfully")
    void testJtokkitAvailable() {
        assertTrue(TokenUtil.isExactCountingAvailable(), 
            "jtokkit should be available for exact token counting");
    }
    
    @Test
    @DisplayName("Should count tokens accurately for English text")
    void testEstimateTokensEnglish() {
        // Known token counts for GPT-4 cl100k_base encoding
        String text = "Hello, world!";
        int tokens = TokenUtil.estimateTokens(text);
        
        // "Hello, world!" = 4 tokens in cl100k_base
        assertEquals(4, tokens, "Simple greeting should be 4 tokens");
    }
    
    @Test
    @DisplayName("Should count tokens for longer text")
    void testEstimateTokensLongerText() {
        String text = "The quick brown fox jumps over the lazy dog.";
        int tokens = TokenUtil.estimateTokens(text);
        
        // This sentence is typically 10 tokens in cl100k_base
        assertTrue(tokens >= 9 && tokens <= 11, 
            "Sentence should be approximately 10 tokens, got: " + tokens);
    }
    
    @Test
    @DisplayName("Should return 0 for empty string")
    void testEstimateTokensEmpty() {
        assertEquals(0, TokenUtil.estimateTokens(""));
    }
    
    @Test
    @DisplayName("Should handle null safely")
    void testEstimateTokensSafe() {
        assertEquals(0, TokenUtil.estimateTokensSafe(null));
        assertEquals(0, TokenUtil.estimateTokensSafe(""));
    }
    
    @Test
    @DisplayName("Should encode and decode text correctly")
    void testEncodeAndDecode() {
        String original = "Hello, world!";
        List<Integer> tokens = TokenUtil.encode(original);
        
        assertFalse(tokens.isEmpty(), "Should produce tokens");
        
        String decoded = TokenUtil.decode(tokens);
        assertEquals(original, decoded, "Decoded text should match original");
    }
    
    @Test
    @DisplayName("Should truncate text to token limit")
    void testTruncateToTokenLimit() {
        String longText = "This is a very long text that will need to be truncated because it exceeds the token limit we set for this test.";
        
        String truncated = TokenUtil.truncateToTokenLimit(longText, 10);
        
        int truncatedTokens = TokenUtil.estimateTokens(truncated);
        assertTrue(truncatedTokens <= 10, 
            "Truncated text should be within limit, got: " + truncatedTokens);
        assertTrue(truncated.endsWith("..."), "Truncated text should end with ellipsis");
    }
    
    @Test
    @DisplayName("Should not truncate text within limit")
    void testTruncateWithinLimit() {
        String shortText = "Hello, world!";
        
        String result = TokenUtil.truncateToTokenLimit(shortText, 100);
        
        assertEquals(shortText, result, "Short text should not be truncated");
    }
    
    @Test
    @DisplayName("Should chunk text by tokens")
    void testChunkText() {
        String longText = "First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence.";
        
        List<String> chunks = TokenUtil.chunkText(longText, 10, 2);
        
        assertFalse(chunks.isEmpty(), "Should produce chunks");
        
        for (String chunk : chunks) {
            int tokens = TokenUtil.estimateTokens(chunk);
            assertTrue(tokens <= 12, // Allow small overflow due to sentence boundaries
                "Each chunk should be within limit, got: " + tokens);
        }
    }
    
    @Test
    @DisplayName("Should calculate budget allocation correctly")
    void testBudgetAllocation() {
        TokenUtil.BudgetAllocation allocation = TokenUtil.BudgetAllocation.withDefaults(4000);
        
        assertEquals(1600, allocation.entityBudget(), "Entity budget should be 40%");
        assertEquals(1200, allocation.relationBudget(), "Relation budget should be 30%");
        assertEquals(1200, allocation.chunkBudget(), "Chunk budget should be 30%");
        assertEquals(4000, allocation.totalBudget(), "Total should be 4000");
    }
    
    @Test
    @DisplayName("Should detect budget exceeding")
    void testWouldExceedBudget() {
        // "This is additional text that will definitely exceed" = ~10 tokens
        assertTrue(TokenUtil.wouldExceedBudget(95, "This is additional text that will definitely exceed", 100));
        assertFalse(TokenUtil.wouldExceedBudget(10, "Hi", 100));
    }
    
    @Test
    @DisplayName("Should count words correctly")
    void testCountWords() {
        assertEquals(0, TokenUtil.countWords(""));
        assertEquals(1, TokenUtil.countWords("Hello"));
        assertEquals(5, TokenUtil.countWords("The quick brown fox jumps"));
    }
    
    @Test
    @DisplayName("Approximate method should still work as fallback")
    void testApproximateMethod() {
        String text = "Hello, world!";
        int approximate = TokenUtil.estimateTokensApproximate(text);
        
        // 13 chars / 4 = ~3.25, rounds up to 4
        assertEquals(4, approximate, "Approximate should use ~4 chars per token");
    }
    
    @Test
    @DisplayName("Should handle special characters in token counting")
    void testSpecialCharacters() {
        String text = "Hello! @#$%^&*() World";
        int tokens = TokenUtil.estimateTokens(text);
        
        assertTrue(tokens > 0, "Should count tokens for text with special chars");
    }
    
    @Test
    @DisplayName("Should handle multilingual text")
    void testMultilingualText() {
        String text = "Hello 世界 مرحبا мир";
        int tokens = TokenUtil.estimateTokens(text);
        
        assertTrue(tokens > 0, "Should count tokens for multilingual text");
        // Non-English text typically uses more tokens
        assertTrue(tokens > 4, "Multilingual text should use more tokens");
    }
}
