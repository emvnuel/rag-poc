package br.edu.ifba.lightrag.core;

/**
 * Types of cached LLM extraction results.
 */
public enum CacheType {
    /**
     * Initial entity/relation extraction from a chunk.
     */
    ENTITY_EXTRACTION,
    
    /**
     * Follow-up gleaning pass to capture missed entities.
     */
    GLEANING,
    
    /**
     * LLM-based description summarization result.
     */
    SUMMARIZATION,
    
    /**
     * Query keyword extraction (high-level and low-level keywords).
     */
    KEYWORD_EXTRACTION
}
