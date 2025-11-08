package br.edu.ifba.document;

import java.util.List;
import java.util.UUID;

import br.edu.ifba.lightrag.LightRAGService;
import br.edu.ifba.lightrag.core.QueryParam;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SearchService {

    private static final Logger LOG = Logger.getLogger(SearchService.class);

    @Inject
    LightRAGService lightragService;

    @ConfigProperty(name = "lightrag.query.mode", defaultValue = "HYBRID")
    String queryMode;

    /**
     * Searches documents using LightRAG knowledge graph query.
     * LightRAG provides a synthesized answer rather than individual chunk results.
     * 
     * @param query The search query
     * @param projectId The project UUID to search within
     * @param maxResults Not used with LightRAG (kept for API compatibility)
     * @return SearchResponse with the LightRAG answer as a single result
     */
    public SearchResponse search(final String query, final UUID projectId, final Integer maxResults) {
        LOG.infof("Executing LightRAG search for: '%s' in project: %s", query, projectId);

        try {
            // Parse query mode from config
            final QueryParam.Mode mode = parseQueryMode(queryMode);
            
            // Execute LightRAG query
            final String answer = lightragService.query(query, mode, projectId).join();
            
            LOG.infof("LightRAG query completed - answer length: %d characters", answer.length());

            // Return the answer as a single search result
            // LightRAG provides a synthesized answer, not individual chunks
            final SearchResult result = new SearchResult(
                    null,           // No specific document ID
                    answer,         // The synthesized answer
                    null,           // No chunk index
                    "LightRAG",     // Source is LightRAG
                    0.0             // No distance score (LightRAG uses graph-based retrieval)
            );

            return new SearchResponse(List.of(result));
            
        } catch (Exception e) {
            LOG.errorf(e, "Error executing LightRAG search for query: '%s'", query);
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parses query mode from configuration string.
     * 
     * @param modeString The mode string (LOCAL, GLOBAL, HYBRID, NAIVE, MIX)
     * @return The corresponding QueryParam.Mode enum
     */
    private QueryParam.Mode parseQueryMode(final String modeString) {
        try {
            return QueryParam.Mode.valueOf(modeString.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid query mode '%s', defaulting to HYBRID", modeString);
            return QueryParam.Mode.HYBRID;
        }
    }
}
