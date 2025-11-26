package br.edu.ifba.document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Inject
    DocumentRepository documentRepository;

    @ConfigProperty(name = "lightrag.query.mode", defaultValue = "LOCAL")
    String queryMode;

    /**
     * Searches documents using LightRAG knowledge graph query.
     * Returns both the synthesized answer AND the source chunks used to generate it.
     * The number of chunks returned is controlled by lightrag.query.chunk.top.k configuration.
     * 
     * @param query The search query
     * @param projectId The project UUID to search within
     * @return SearchResponse with the answer and source chunks as SearchResults with citations
     */
    public SearchResponse search(final String query, final UUID projectId) {
        return search(query, projectId, null);
    }
    
    /**
     * Searches documents using LightRAG knowledge graph query with optional reranking control.
     * Returns both the synthesized answer AND the source chunks used to generate it.
     * The number of chunks returned is controlled by lightrag.query.chunk.top.k configuration.
     * 
     * @param query The search query
     * @param projectId The project UUID to search within
     * @param enableRerank Optional flag to enable/disable reranking (null uses global config)
     * @return SearchResponse with the answer and source chunks as SearchResults with citations
     */
    public SearchResponse search(final String query, final UUID projectId, final Boolean enableRerank) {
        LOG.infof("Executing LightRAG search for: '%s' in project: %s, rerank: %s", query, projectId, enableRerank);

        try {
            // Parse query mode from config
            final QueryParam.Mode mode = parseQueryMode(queryMode);
            
            // Execute LightRAG query and get result with sources
            final LightRAGQueryResult queryResult = lightragService.query(query, mode, projectId, enableRerank).join();
            
            LOG.infof("LightRAG query completed - answer length: %d characters, sources: %d", 
                    queryResult.answer().length(), queryResult.totalSources());

            // Build search results list with answer first, then source chunks
            final List<SearchResult> results = new ArrayList<>();
            
            // First result: The synthesized answer with citations
            results.add(new SearchResult(
                    null,                       // No chunk ID for the answer
                    null,                       // No specific document ID for the answer
                    queryResult.answer(),       // The synthesized answer with [1], [2] citations
                    null,                       // No chunk index
                    "LightRAG Answer",          // Source type
                    0.0                         // Most relevant (distance 0)
            ));
            
            // Add source chunks as additional results
            // ONLY include sources with document IDs (entities without document IDs cannot be cited)
            // The number of chunks is controlled by lightrag.query.chunk.top.k
            final int limit = queryResult.sourceChunks().size();
            
            // First pass: collect all document UUIDs and valid sources
            final Set<UUID> documentIds = new HashSet<>();
            final List<LightRAGQueryResult.SourceChunk> validSources = new ArrayList<>();
            
            for (int i = 0; i < limit && i < queryResult.sourceChunks().size(); i++) {
                final LightRAGQueryResult.SourceChunk source = queryResult.sourceChunks().get(i);
                
                // Skip sources without document IDs (e.g., knowledge graph entities)
                    // Also skip entity-type chunks explicitly to prevent them from being cited
                if (source.documentId() == null || "entity".equals(source.type())) {
                    LOG.debugf("Skipping non-citable source: %s (type: %s, documentId: %s)", 
                              source.chunkId(), source.type(), source.documentId());
                    continue;
                }
                
                // Parse document UUID from documentId
                try {
                    UUID documentUuid = UUID.fromString(source.documentId());
                    documentIds.add(documentUuid);
                    validSources.add(source);
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Could not parse document UUID from: %s, skipping source", source.documentId());
                }
            }
            
            // Batch query documents to get filenames
            final Map<UUID, String> documentFileNames = new HashMap<>();
            if (!documentIds.isEmpty()) {
                LOG.debugf("Loading filenames for %d documents", documentIds.size());
                for (UUID docId : documentIds) {
                    try {
                        Document doc = documentRepository.findById(docId);
                        if (doc != null) {
                            documentFileNames.put(docId, doc.getFileName());
                        } else {
                            LOG.warnf("Document not found for UUID: %s", docId);
                            documentFileNames.put(docId, "Unknown Document");
                        }
                    } catch (Exception e) {
                        LOG.warnf("Error loading document %s: %s", docId, e.getMessage());
                        documentFileNames.put(docId, "Unknown Document");
                    }
                }
            }
            
            // Second pass: create search results with filenames
            int sourceCount = 0;
            for (LightRAGQueryResult.SourceChunk source : validSources) {
                UUID documentUuid = UUID.fromString(source.documentId());
                String fileName = documentFileNames.getOrDefault(documentUuid, "Unknown Document");
                
                // Format source with filename and chunk index
                String sourceDescription = source.chunkIndex() > 0 
                    ? String.format("%s - chunk %d", fileName, source.chunkIndex())
                    : fileName;
                
                sourceCount++;
                results.add(new SearchResult(
                        source.chunkId(),                                // Chunk ID
                        documentUuid,                                    // Document UUID
                        source.content(),                                // Chunk content
                        source.chunkIndex(),                             // Chunk index
                        sourceDescription,                               // Source with filename and chunk
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
