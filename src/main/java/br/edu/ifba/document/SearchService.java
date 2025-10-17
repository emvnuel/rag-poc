package br.edu.ifba.document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SearchService {

    private static final Logger LOG = Logger.getLogger(SearchService.class);
    private static final int DEFAULT_LIMIT = 5;

    @Inject
    EmbeddingRepository embeddingRepository;

    @Inject
    @RestClient
    LlmEmbeddingClient embeddingClient;

    @ConfigProperty(name = "embedding.model")
    String embeddingModel;

    @ConfigProperty(name = "vector.search.probes", defaultValue = "20")
    int vectorSearchProbes;

    @ConfigProperty(name = "vector.search.distance.gap.threshold", defaultValue = "0.03")
    double distanceGapThreshold;

    @Transactional
    public SearchResponse search(final String query, final UUID projectId, final Integer maxResults) {
        final int searchLimit = maxResults != null ? maxResults : DEFAULT_LIMIT;

        LOG.infof("Searching for: '%s' in project: %s with max results: %d", query, projectId, searchLimit);

        final EmbeddingRequest request = new EmbeddingRequest(embeddingModel, query);
        final EmbeddingResponse response = embeddingClient.embed(request);

        final List<Double> embedding = response.data().getFirst().embedding();
        final String vectorString = convertToVectorString(embedding);

        final List<Object[]> results = embeddingRepository.findSimilarEmbeddingsByProject(
                vectorString, 
                projectId,
                searchLimit, 
                vectorSearchProbes
        );

        final List<SearchResult> searchResults = mapToSearchResults(results);
        final List<SearchResult> filteredResults = filterByDistanceGap(searchResults);

        LOG.infof("Found %d similar embeddings, filtered to %d relevant results", 
                searchResults.size(), filteredResults.size());

        return new SearchResponse(filteredResults);
    }

    private String convertToVectorString(final List<Double> embedding) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private List<SearchResult> mapToSearchResults(final List<Object[]> results) {
        final List<SearchResult> searchResults = new ArrayList<>();
        
        for (Object[] row : results) {
            final UUID id = (UUID) row[0];
            final String chunkText = (String) row[1];
            final Integer chunkIndex = (Integer) row[2];
            final String fileName = (String) row[3];
            final Double distance = ((Number) row[4]).doubleValue();

            searchResults.add(new SearchResult(id, chunkText, chunkIndex, fileName, distance));
        }
        
        return searchResults;
    }

    private List<SearchResult> filterByDistanceGap(final List<SearchResult> results) {
        if (results.isEmpty()) {
            return results;
        }

        if (results.size() == 1) {
            return results;
        }

        final List<SearchResult> filtered = new ArrayList<>();
        filtered.add(results.get(0));

        for (int i = 1; i < results.size(); i++) {
            final double currentDistance = results.get(i).distance();
            final double previousDistance = results.get(i - 1).distance();
            final double gap = currentDistance - previousDistance;

            if (gap > distanceGapThreshold) {
                LOG.infof("Filtering results after index %d due to distance gap: %.4f > %.4f", 
                        i - 1, gap, distanceGapThreshold);
                break;
            }

            filtered.add(results.get(i));
        }

        return filtered;
    }
}
