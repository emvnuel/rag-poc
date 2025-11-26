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
 * REST client interface for Jina Rerank API.
 * 
 * <p>API documentation: <a href="https://jina.ai/reranker/">Jina Reranker</a>
 * 
 * <p>This client is registered with the key "jina-rerank" and configured via:
 * <pre>
 * quarkus.rest-client."jina-rerank".url=https://api.jina.ai
 * quarkus.rest-client."jina-rerank".read-timeout=3000
 * quarkus.rest-client."jina-rerank".connect-timeout=2000
 * </pre>
 */
@RegisterRestClient(configKey = "jina-rerank")
@Path("/v1")
public interface JinaRerankClient {
    
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
    JinaRerankResponse rerank(
        @HeaderParam("Authorization") String authorization,
        JinaRerankRequest request
    );
    
    /**
     * Request body for Jina rerank API.
     *
     * @param model     the rerank model to use (e.g., "jina-reranker-v2-base-multilingual")
     * @param query     the query to compare documents against
     * @param documents list of document texts to rerank
     * @param topN      maximum number of results to return
     */
    record JinaRerankRequest(
        String model,
        String query,
        List<String> documents,
        int topN
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
        public static JinaRerankRequest of(String model, String query, List<String> documents, int topN) {
            return new JinaRerankRequest(model, query, documents, topN);
        }
    }
    
    /**
     * Response from Jina rerank API.
     *
     * @param model   the model used
     * @param results list of reranked results
     * @param usage   token usage information
     */
    record JinaRerankResponse(
        String model,
        List<JinaRerankResult> results,
        JinaUsage usage
    ) {}
    
    /**
     * A single reranked result from Jina.
     *
     * @param index          original index of the document
     * @param relevanceScore relevance score (typically 0.0 to 1.0)
     * @param document       optionally returned document details
     */
    record JinaRerankResult(
        int index,
        double relevanceScore,
        JinaDocument document
    ) {}
    
    /**
     * Document details in rerank result.
     *
     * @param text the document text
     */
    record JinaDocument(String text) {}
    
    /**
     * Token usage from Jina API.
     *
     * @param totalTokens total tokens used in the request
     */
    record JinaUsage(int totalTokens) {}
}
