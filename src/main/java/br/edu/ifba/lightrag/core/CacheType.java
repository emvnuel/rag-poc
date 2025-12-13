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
    KEYWORD_EXTRACTION,
    
    /**
     * Cached query response to avoid duplicate LLM calls for identical queries.
     * 
     * <p>Cache key is computed from: projectId + query + mode + topK parameters.</p>
     * <p>Ported from official LightRAG Python LIGHTRAG_LLM_CACHE functionality.</p>
     */
    QUERY_RESPONSE
}
