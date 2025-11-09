package br.edu.ifba.lightrag.adapters;

import br.edu.ifba.document.EmbeddingRequest;
import br.edu.ifba.document.EmbeddingResponse;
import br.edu.ifba.document.LlmEmbeddingClient;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Adapter that bridges the existing Quarkus LlmEmbeddingClient to LightRAG's EmbeddingFunction interface.
 * This allows LightRAG to use the Quarkus-managed embedding client for all text-to-vector operations.
 */
@ApplicationScoped
public class QuarkusEmbeddingAdapter implements EmbeddingFunction {

    private static final Logger LOG = Logger.getLogger(QuarkusEmbeddingAdapter.class);
    private static final ClassLoader QUARKUS_CLASSLOADER = QuarkusEmbeddingAdapter.class.getClassLoader();

    @Inject
    @RestClient
    LlmEmbeddingClient embeddingClient;

    @ConfigProperty(name = "embedding.model")
    String embeddingModel;

    @ConfigProperty(name = "lightrag.vector.dimension", defaultValue = "384")
    Integer vectorDimension;

    @Override
    public CompletableFuture<List<float[]>> embed(@NotNull final List<String> texts) {
        // Set classloader before any operations
        final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(QUARKUS_CLASSLOADER);
        
        try {
            LOG.debugf("LightRAG embedding request - texts count: %d, thread: %s, classloader: %s", 
                    Integer.valueOf(texts.size()), 
                    Thread.currentThread().getName(),
                    QUARKUS_CLASSLOADER.getClass().getName());

            if (texts.isEmpty()) {
                LOG.warn("Empty text list provided for embedding");
                return CompletableFuture.completedFuture(List.of());
            }

            LOG.debugf("Using classloader: %s", QUARKUS_CLASSLOADER.getClass().getName());

            // Create request with list of texts
            final EmbeddingRequest request = new EmbeddingRequest(embeddingModel, texts);

            LOG.debugf("Calling embedding API with model: %s", embeddingModel);

            final EmbeddingResponse response = embeddingClient.embed(request);

            if (response.getData() == null || response.getData().isEmpty()) {
                throw new RuntimeException("Embedding API returned no data");
            }

            // Validate we got the expected number of embeddings
            if (response.getData().size() != texts.size()) {
                LOG.warnf("Expected %d embeddings but received %d",
                        Integer.valueOf(texts.size()),
                        Integer.valueOf(response.getData().size()));
            }

            // Convert List<Double> to float[] for each embedding
            final List<float[]> embeddings = new ArrayList<>(response.getData().size());
            
            for (final EmbeddingResponse.Embedding embeddingData : response.getData()) {
                final List<Double> doubleVector = embeddingData.getEmbedding();
                
                if (doubleVector == null || doubleVector.isEmpty()) {
                    throw new RuntimeException("Embedding API returned null or empty vector");
                }

                // Validate dimension matches configuration
                final int actualDimension = doubleVector.size();
                if (actualDimension != vectorDimension) {
                    LOG.warnf("Vector dimension mismatch: expected %d but got %d - will truncate to configured dimension",
                            Integer.valueOf(vectorDimension),
                            Integer.valueOf(actualDimension));
                }

                // Convert Double list to float array, truncating if necessary
                // This handles cases where the model produces more dimensions than we need
                // (e.g., qwen3-embedding:8b produces 4096 but we need 4000 for HNSW index limit)
                final int targetDimension = Math.min(actualDimension, vectorDimension);
                final float[] floatVector = new float[targetDimension];
                for (int i = 0; i < targetDimension; i++) {
                    floatVector[i] = doubleVector.get(i).floatValue();
                }
                
                embeddings.add(floatVector);
            }

            LOG.debugf("Successfully generated %d embeddings with dimension %d",
                    Integer.valueOf(embeddings.size()),
                    Integer.valueOf(embeddings.get(0).length));

            return CompletableFuture.completedFuture(embeddings);
                
        } catch (Exception e) {
            LOG.errorf(e, "Error calling embedding API via QuarkusEmbeddingAdapter");
            return CompletableFuture.failedFuture(new RuntimeException("Failed to generate embeddings: " + e.getMessage(), e));
        } finally {
            // Restore original classloader
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}
