package br.edu.ifba.document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.edu.ifba.lightrag.LightRAGService;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
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

    @ConfigProperty(name = "lightrag.query.mode", defaultValue = "LOCAL")
    String queryMode;

    /**
     * Searches documents using LightRAG knowledge graph query.
     * Returns both the synthesized answer AND the source chunks used to generate it.
     * 
     * @param query The search query
     * @param projectId The project UUID to search within
     * @param maxResults Maximum number of source results to return (limits source chunks)
     * @return SearchResponse with the answer and source chunks as SearchResults with citations
     */
    public SearchResponse search(final String query, final UUID projectId, final Integer maxResults) {
        LOG.infof("Executing LightRAG search for: '%s' in project: %s", query, projectId);

        try {
            // Parse query mode from config
            final QueryParam.Mode mode = parseQueryMode(queryMode);
            
            // Execute LightRAG query and get result with sources
            final LightRAGQueryResult queryResult = lightragService.query(query, mode, projectId).join();
            
            LOG.infof("LightRAG query completed - answer length: %d characters, sources: %d", 
                    queryResult.answer().length(), queryResult.totalSources());

            // Build search results list with answer first, then source chunks
            final List<SearchResult> results = new ArrayList<>();
            
            // First result: The synthesized answer with citations
            results.add(new SearchResult(
                    null,                       // No specific document ID for the answer
                    queryResult.answer(),       // The synthesized answer with [1], [2] citations
                    null,                       // No chunk index
                    "LightRAG Answer",          // Source type
                    0.0                         // Most relevant (distance 0)
            ));
            
            // Add source chunks as additional results
            // ONLY include sources with document IDs (entities without document IDs cannot be cited)
            final int limit = maxResults != null ? Math.min(maxResults, queryResult.sourceChunks().size()) 
                                                  : queryResult.sourceChunks().size();
            
            int sourceCount = 0;
            for (int i = 0; i < limit && i < queryResult.sourceChunks().size(); i++) {
                final LightRAGQueryResult.SourceChunk source = queryResult.sourceChunks().get(i);
                
                // Skip sources without document IDs (e.g., knowledge graph entities)
                if (source.documentId() == null) {
                    LOG.debugf("Skipping source without document ID: %s (type: %s)", 
                              source.chunkId(), source.type());
                    continue;
                }
                
                // Parse document UUID from documentId
                UUID documentUuid = null;
                try {
                    documentUuid = UUID.fromString(source.documentId());
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Could not parse document UUID from: %s, skipping source", source.documentId());
                    continue;
                }
                
                sourceCount++;
                results.add(new SearchResult(
                        documentUuid,                                    // Document UUID
                        source.content(),                                // Chunk content
                        source.chunkIndex(),                             // Chunk index
                        String.format("Source - %s", source.type()),     // Source type (removed citation number)
                        source.relevanceScore()                          // Relevance score (distance)
                ));
            }
            
            LOG.infof("Filtered to %d citable sources (from %d total sources)", sourceCount, queryResult.totalSources());

            return new SearchResponse(results);
            
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
