package br.edu.ifba.lightrag.utils;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for token counting and text chunking.
 * Uses approximations for token counting (more accurate implementations
 * would use tiktoken or similar tokenizer libraries).
 */
public final class TokenUtil {
    
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final double AVG_CHARS_PER_TOKEN = 4.0; // Rough approximation
    
    private TokenUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Estimates token count for a given text.
     * Uses a simple heuristic: ~4 characters per token on average.
     * For production use, integrate a proper tokenizer (e.g., tiktoken port).
     *
     * @param text The input text
     * @return Estimated token count
     */
    public static int estimateTokens(@NotNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / AVG_CHARS_PER_TOKEN);
    }
    
    /**
     * Chunks text into smaller pieces based on token limit.
     * Attempts to split on sentence boundaries when possible.
     *
     * @param text The input text
     * @param maxTokens Maximum tokens per chunk
     * @param overlapTokens Number of overlapping tokens between chunks
     * @return List of text chunks
     */
    @NotNull
    public static List<String> chunkText(
        @NotNull String text,
        int maxTokens,
        int overlapTokens
    ) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException(
                "overlapTokens must be non-negative and less than maxTokens"
            );
        }
        
        List<String> chunks = new ArrayList<>();
        if (text.isEmpty()) {
            return chunks;
        }
        
        // Split into sentences (simple approach)
        String[] sentences = text.split("(?<=[.!?])\\s+");
        
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        
        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);
            
            // If single sentence exceeds maxTokens, split it by characters
            if (sentenceTokens > maxTokens) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                
                // Split long sentence into character-based chunks
                chunks.addAll(chunkByCharacters(sentence, maxTokens, overlapTokens));
                continue;
            }
            
            // Check if adding this sentence would exceed limit
            if (currentTokens + sentenceTokens > maxTokens && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                
                // Handle overlap by keeping last few tokens
                if (overlapTokens > 0) {
                    String overlap = getLastNTokens(currentChunk.toString(), overlapTokens);
                    currentChunk = new StringBuilder(overlap);
                    currentTokens = estimateTokens(overlap);
                } else {
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
            }
            
            currentChunk.append(sentence).append(" ");
            currentTokens += sentenceTokens;
        }
        
        // Add remaining chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        
        return chunks;
    }
    
    /**
     * Chunks text by characters when sentence-based chunking isn't possible.
     */
    @NotNull
    private static List<String> chunkByCharacters(
        @NotNull String text,
        int maxTokens,
        int overlapTokens
    ) {
        List<String> chunks = new ArrayList<>();
        int maxChars = (int) (maxTokens * AVG_CHARS_PER_TOKEN);
        int overlapChars = (int) (overlapTokens * AVG_CHARS_PER_TOKEN);
        int stride = maxChars - overlapChars;
        
        for (int i = 0; i < text.length(); i += stride) {
            int end = Math.min(i + maxChars, text.length());
            chunks.add(text.substring(i, end));
            
            if (end >= text.length()) {
                break;
            }
        }
        
        return chunks;
    }
    
    /**
     * Extracts the last N tokens from text (approximation).
     */
    @NotNull
    private static String getLastNTokens(@NotNull String text, int n) {
        int targetChars = (int) (n * AVG_CHARS_PER_TOKEN);
        if (text.length() <= targetChars) {
            return text;
        }
        return text.substring(text.length() - targetChars);
    }
    
    /**
     * Counts words in text (simple whitespace-based).
     */
    public static int countWords(@NotNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return WHITESPACE.split(text.trim()).length;
    }
}
