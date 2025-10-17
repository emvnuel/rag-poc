package br.edu.ifba.document;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "llm-embedding")
public interface LlmEmbeddingClient {

    @POST
    @Path("/v1/embeddings")
    EmbeddingResponse embed(EmbeddingRequest request);
}
