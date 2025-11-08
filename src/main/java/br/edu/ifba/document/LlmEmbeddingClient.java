package br.edu.ifba.document;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "llm-embedding")
@ClientHeaderParam(name = "Authorization", value = "{lookupAuth}")
public interface LlmEmbeddingClient {

    @POST
    @Path("/embeddings")
    EmbeddingResponse embed(EmbeddingRequest request);

    default String lookupAuth() {
        return ConfigProvider.getConfig()
            .getOptionalValue("llm-embedding.api-key", String.class)
            .map(key -> "Bearer " + key)
            .orElse(null);
    }
}
