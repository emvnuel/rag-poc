package br.edu.ifba.lightrag;

import br.edu.ifba.lightrag.adapters.QuarkusEmbeddingAdapter;
import br.edu.ifba.lightrag.adapters.QuarkusLLMAdapter;
import br.edu.ifba.lightrag.core.LightRAG;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.storage.impl.AgeConfig;
import br.edu.ifba.lightrag.storage.impl.AgeGraphStorage;
import br.edu.ifba.lightrag.storage.impl.InMemoryDocStatusStorage;
import br.edu.ifba.lightrag.storage.impl.JsonKVStorage;
import br.edu.ifba.lightrag.storage.impl.PgVectorStorage;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service that manages LightRAG instance with PostgreSQL (PgVector + Apache AGE) storage.
 * This service integrates LightRAG's knowledge graph extraction and querying capabilities
 * with the existing document processing pipeline.
 */
@ApplicationScoped
@Startup
public class LightRAGService {

    private static final Logger LOG = Logger.getLogger(LightRAGService.class);

    @Inject
    QuarkusLLMAdapter llmAdapter;

    @Inject
    QuarkusEmbeddingAdapter embeddingAdapter;

    @Inject
    PgVectorStorage chunkVectorStorage;

    @Inject
    PgVectorStorage entityVectorStorage;

    @Inject
    AgeGraphStorage graphStorage;

    @Inject
    AgeConfig ageConfig;

    @ConfigProperty(name = "lightrag.chunk.size", defaultValue = "1200")
    int chunkSize;

    @ConfigProperty(name = "lightrag.chunk.overlap", defaultValue = "100")
    int chunkOverlap;

    @ConfigProperty(name = "lightrag.query.top.k", defaultValue = "10")
    int topK;

    @ConfigProperty(name = "lightrag.query.chunk.top.k", defaultValue = "5")
    int chunkTopK;

    @ConfigProperty(name = "lightrag.storage.working.dir", defaultValue = "./lightrag-data")
    String workingDir;

    @ConfigProperty(name = "lightrag.query.system.prompt.local")
    String localSystemPrompt;

    @ConfigProperty(name = "lightrag.query.system.prompt.global")
    String globalSystemPrompt;

    @ConfigProperty(name = "lightrag.query.system.prompt.hybrid")
    String hybridSystemPrompt;

    @ConfigProperty(name = "lightrag.query.system.prompt.naive")
    String naiveSystemPrompt;

    @ConfigProperty(name = "lightrag.query.system.prompt.mix")
    String mixSystemPrompt;

    @ConfigProperty(name = "lightrag.query.system.prompt.bypass")
    String bypassSystemPrompt;

    @ConfigProperty(name = "lightrag.entity.extraction.system.prompt")
    String entityExtractionSystemPrompt;

    @ConfigProperty(name = "lightrag.entity.types")
    String entityTypes;

    @ConfigProperty(name = "lightrag.extraction.language")
    String extractionLanguage;

    @ConfigProperty(name = "lightrag.entity.extraction.user.prompt")
    String entityExtractionUserPrompt;

    @ConfigProperty(name = "lightrag.kg.extraction.batch.size", defaultValue = "20")
    int kgExtractionBatchSize;

    @ConfigProperty(name = "lightrag.embedding.batch.size", defaultValue = "32")
    int embeddingBatchSize;

    @ConfigProperty(name = "lightrag.entity.description.max.length", defaultValue = "1000")
    int entityDescriptionMaxLength;

    @ConfigProperty(name = "lightrag.entity.description.separator", defaultValue = " | ")
    String entityDescriptionSeparator;

    private LightRAG lightRAG;
    private JsonKVStorage chunkKVStorage;
    private JsonKVStorage llmCacheStorage;
    private InMemoryDocStatusStorage docStatusStorage;

    /**
     * Initializes LightRAG instance with all storage backends.
     * Called automatically on application startup.
     */
    @PostConstruct
    public void initialize() {
        try {
            LOG.info("Initializing LightRAG service with PostgreSQL storage...");

            // Initialize AGE graph database
            ageConfig.initialize();
            LOG.info("Apache AGE graph initialized");

            // Create working directory for KV storage
            final Path workingPath = Paths.get(workingDir);
            workingPath.toFile().mkdirs();

            // Initialize KV storages (JSON file-based for chunks and LLM cache)
            this.chunkKVStorage = new JsonKVStorage(workingPath.resolve("chunks.json").toString());
            this.llmCacheStorage = new JsonKVStorage(workingPath.resolve("llm_cache.json").toString());
            this.docStatusStorage = new InMemoryDocStatusStorage();

            // Build LightRAG configuration
            final LightRAG.LightRAGConfig config = new LightRAG.LightRAGConfig(
                    chunkSize,
                    chunkOverlap,
                    4000,  // maxTokens
                    topK,
                    true,  // enableCache
                    kgExtractionBatchSize,
                    embeddingBatchSize,
                    entityDescriptionMaxLength,
                    entityDescriptionSeparator
            );

            // Build LightRAG instance
            this.lightRAG = LightRAG.builder()
                    .config(config)
                    .llmFunction(llmAdapter)
                    .embeddingFunction(embeddingAdapter)
                    .chunkStorage(chunkKVStorage)
                    .llmCacheStorage(llmCacheStorage)
                    .chunkVectorStorage(chunkVectorStorage)
                    .entityVectorStorage(entityVectorStorage)
                    .graphStorage(graphStorage)
                    .docStatusStorage(docStatusStorage)
                    .localSystemPrompt(localSystemPrompt)
                    .globalSystemPrompt(globalSystemPrompt)
                    .hybridSystemPrompt(hybridSystemPrompt)
                    .naiveSystemPrompt(naiveSystemPrompt)
                    .mixSystemPrompt(mixSystemPrompt)
                    .bypassSystemPrompt(bypassSystemPrompt)
                    .entityExtractionSystemPrompt(entityExtractionSystemPrompt)
                    .entityTypes(entityTypes)
                    .extractionLanguage(extractionLanguage)
                    .entityExtractionUserPrompt(entityExtractionUserPrompt)
                    .build();

            // Initialize LightRAG (async operation)
            lightRAG.initialize()
                    .thenRun(() -> LOG.info("LightRAG service initialized successfully"))
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "Failed to initialize LightRAG service");
                        return null;
                    })
                    .join();

        } catch (Exception e) {
            LOG.errorf(e, "Error during LightRAG service initialization");
            throw new RuntimeException("Failed to initialize LightRAG service", e);
        }
    }

    /**
     * Cleans up resources on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        LOG.info("Shutting down LightRAG service...");
        try {
            if (chunkKVStorage != null) {
                chunkKVStorage.close();
            }
            if (llmCacheStorage != null) {
                llmCacheStorage.close();
            }
            if (docStatusStorage != null) {
                docStatusStorage.close();
            }
            LOG.info("LightRAG service shut down successfully");
        } catch (Exception e) {
            LOG.errorf(e, "Error during LightRAG service shutdown");
        }
    }

    /**
     * Inserts a document into the LightRAG knowledge graph.
     * This processes the document through chunking, entity extraction, and graph construction.
     *
     * @param documentId The document UUID
     * @param content The document content
     * @param fileName The document file name
     * @param projectId The project UUID
     * @return CompletableFuture with the LightRAG document ID
     */
    public CompletableFuture<String> insertDocument(
            final UUID documentId,
            final String content,
            final String fileName,
            final UUID projectId) {

        LOG.infof("Inserting document into LightRAG - documentId: %s, projectId: %s", documentId, projectId);

        final Map<String, Object> metadata = Map.of(
                "document_id", documentId.toString(),
                "project_id", projectId.toString(),
                "filepath", fileName,
                "source_id", documentId.toString()
        );

        return lightRAG.insertWithId(documentId.toString(), content, metadata)
                .thenApply(lightragDocId -> {
                    LOG.infof("Document %s successfully inserted into LightRAG with ID: %s", 
                            documentId, lightragDocId);
                    return lightragDocId;
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to insert document %s into LightRAG", documentId);
                    throw new RuntimeException("Failed to insert document into LightRAG: " + ex.getMessage(), ex);
                });
    }

    /**
     * Queries the LightRAG knowledge graph.
     *
     * @param query The query string
     * @param mode The query mode (LOCAL, GLOBAL, HYBRID, NAIVE, MIX)
     * @param projectId The project UUID (for filtering - currently not implemented in base LightRAG)
     * @return CompletableFuture with the query result containing answer and source chunks
     */
    public CompletableFuture<LightRAGQueryResult> query(
            final String query,
            final QueryParam.Mode mode,
            final UUID projectId) {

        LOG.infof("Executing LightRAG query - mode: %s, projectId: %s, query: '%s'", 
                mode, projectId, query);

        final QueryParam param = QueryParam.builder()
                .mode(mode)
                .topK(topK)
                .chunkTopK(chunkTopK)
                .projectId(projectId.toString())
                .build();

        return lightRAG.query(query, param)
                .thenApply(result -> {
                    LOG.infof("LightRAG query completed - answer length: %d characters, sources: %d", 
                            result.answer().length(), result.totalSources());
                    return result;
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to execute LightRAG query");
                    throw new RuntimeException("Failed to execute query: " + ex.getMessage(), ex);
                });
    }

    /**
     * Queries with default HYBRID mode.
     *
     * @param query The query string
     * @param projectId The project UUID
     * @return CompletableFuture with the query result containing answer and source chunks
     */
    public CompletableFuture<LightRAGQueryResult> query(final String query, final UUID projectId) {
        return query(query, QueryParam.Mode.HYBRID, projectId);
    }

    /**
     * Checks if a document already has vectors stored in the database.
     * This is used to prevent duplicate processing and detect race conditions.
     *
     * @param documentId The document UUID
     * @return CompletableFuture<Boolean> true if the document has vectors, false otherwise
     */
    public CompletableFuture<Boolean> hasDocumentVectors(final UUID documentId) {
        LOG.debugf("Checking if document %s has existing vectors", documentId);
        return chunkVectorStorage.hasVectors(documentId.toString());
    }

    /**
     * Gets the underlying LightRAG instance for advanced operations.
     *
     * @return The LightRAG instance
     */
    public LightRAG getLightRAG() {
        return lightRAG;
    }
}
