package br.edu.ifba.lightrag.rerank;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * REST client interface for Cohere Rerank API.
 * 
 * <p>API documentation: <a href="https://docs.cohere.com/reference/rerank">Cohere Rerank</a>
 * 
 * <p>This client is registered with the key "cohere-rerank" and configured via:
 * <pre>
 * quarkus.rest-client."cohere-rerank".url=https://api.cohere.ai
 * quarkus.rest-client."cohere-rerank".read-timeout=3000
 * quarkus.rest-client."cohere-rerank".connect-timeout=2000
 * </pre>
 */
@RegisterRestClient(configKey = "cohere-rerank")
@Path("/v1")
public interface CohereRerankClient {
    
    /**
     * Reranks documents by relevance to a query.
     *
     * @param authorization Bearer token for API authentication
     * @param request       the rerank request
     * @return rerank response with relevance scores
     */
    @POST
    @Path("/rerank")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CohereRerankResponse rerank(
        @HeaderParam("Authorization") String authorization,
        CohereRerankRequest request
    );
    
    /**
     * Request body for Cohere rerank API.
     *
     * @param model            the rerank model to use (e.g., "rerank-english-v3.0")
     * @param query            the query to compare documents against
     * @param documents        list of document texts to rerank
     * @param topN             maximum number of results to return
     * @param returnDocuments  whether to return the document text in response
     */
    record CohereRerankRequest(
        String model,
        String query,
        List<String> documents,
        int topN,
        boolean returnDocuments
    ) {
        /**
         * Creates a rerank request.
         *
         * @param model     the model name
         * @param query     the query string
         * @param documents document texts to rerank
         * @param topN      max results
         * @return new request
         */
        public static CohereRerankRequest of(String model, String query, List<String> documents, int topN) {
            return new CohereRerankRequest(model, query, documents, topN, false);
        }
    }
    
    /**
     * Response from Cohere rerank API.
     *
     * @param id      request ID
     * @param results list of reranked results
     * @param meta    metadata about the request
     */
    record CohereRerankResponse(
        String id,
        List<CohereRerankResult> results,
        CohereMetadata meta
    ) {}
    
    /**
     * A single reranked result from Cohere.
     *
     * @param index          original index of the document
     * @param relevanceScore relevance score (0.0 to 1.0)
     */
    record CohereRerankResult(
        int index,
        double relevanceScore
    ) {}
    
    /**
     * Metadata about token usage from Cohere API.
     *
     * @param apiVersion API version used
     * @param billedUnits billing information
     */
    record CohereMetadata(
        CohereApiVersion apiVersion,
        CohereBilledUnits billedUnits
    ) {}
    
    /**
     * API version information.
     *
     * @param version version string
     */
    record CohereApiVersion(String version) {}
    
    /**
     * Billed units for token tracking.
     *
     * @param searchUnits number of search units billed
     */
    record CohereBilledUnits(int searchUnits) {}
}
