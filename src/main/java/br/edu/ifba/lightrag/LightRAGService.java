package br.edu.ifba.lightrag;

import br.edu.ifba.lightrag.adapters.QuarkusEmbeddingAdapter;
import br.edu.ifba.lightrag.adapters.QuarkusLLMAdapter;
import br.edu.ifba.lightrag.core.LightRAG;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.storage.impl.InMemoryDocStatusStorage;
import br.edu.ifba.lightrag.storage.impl.JsonKVStorage;
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
 * Service that manages LightRAG instance with pluggable storage backends.
 * This service integrates LightRAG's knowledge graph extraction and querying
 * capabilities
 * with the existing document processing pipeline.
 * 
 * <p>
 * The storage backend is selected via configuration:
 * </p>
 * <ul>
 * <li>{@code lightrag.storage.backend=postgresql} - PostgreSQL with pgvector +
 * Apache AGE</li>
 * <li>{@code lightrag.storage.backend=sqlite} - SQLite for local/edge
 * deployment</li>
 * </ul>
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
    VectorStorage vectorStorage;

    @Inject
    GraphStorage graphStorage;

    @Inject
    br.edu.ifba.lightrag.core.EntityResolver entityResolver;

    @Inject
    br.edu.ifba.lightrag.core.DeduplicationConfig deduplicationConfig;

    @Inject
    br.edu.ifba.lightrag.rerank.RerankerFactory rerankerFactory;

    @Inject
    br.edu.ifba.document.CodeChunker codeChunker;

    @Inject
    br.edu.ifba.lightrag.core.CodeExtractionPrompts codeExtractionPrompts;

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

    // Unified prompt approach: single template with source type substitution
    @ConfigProperty(name = "lightrag.query.system.prompt.unified")
    String unifiedSystemPrompt;

    @ConfigProperty(name = "lightrag.query.system.prompt.bypass")
    String bypassSystemPrompt;

    // Source type mappings for each mode
    @ConfigProperty(name = "lightrag.query.source.type.local")
    String sourceTypeLocal;

    @ConfigProperty(name = "lightrag.query.source.type.global")
    String sourceTypeGlobal;

    @ConfigProperty(name = "lightrag.query.source.type.hybrid")
    String sourceTypeHybrid;

    @ConfigProperty(name = "lightrag.query.source.type.naive")
    String sourceTypeNaive;

    @ConfigProperty(name = "lightrag.query.source.type.mix")
    String sourceTypeMix;

    // Source type articles (grammar helper)
    @ConfigProperty(name = "lightrag.query.source.type.article.local")
    String sourceTypeArticleLocal;

    @ConfigProperty(name = "lightrag.query.source.type.article.global")
    String sourceTypeArticleGlobal;

    @ConfigProperty(name = "lightrag.query.source.type.article.hybrid")
    String sourceTypeArticleHybrid;

    @ConfigProperty(name = "lightrag.query.source.type.article.naive")
    String sourceTypeArticleNaive;

    @ConfigProperty(name = "lightrag.query.source.type.article.mix")
    String sourceTypeArticleMix;

    // Response language configuration
    @ConfigProperty(name = "lightrag.response.language")
    String responseLanguage;

    @ConfigProperty(name = "lightrag.entity.extraction.system.prompt")
    String entityExtractionSystemPrompt;

    @ConfigProperty(name = "lightrag.entity.types")
    String entityTypes;

    @ConfigProperty(name = "lightrag.entity.types.code")
    String codeEntityTypes;

    @ConfigProperty(name = "lightrag.relationship.types.code")
    String codeRelationshipTypes;

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
     * Resolves the unified system prompt for a specific query mode by substituting
     * placeholders.
     * 
     * @param mode The query mode (LOCAL, GLOBAL, HYBRID, NAIVE, MIX)
     * @return The resolved system prompt with all placeholders substituted
     */
    private String resolveSystemPromptForMode(String mode) {
        String sourceType;
        String sourceTypeArticle;

        switch (mode.toUpperCase()) {
            case "LOCAL" -> {
                sourceType = sourceTypeLocal;
                sourceTypeArticle = sourceTypeArticleLocal;
            }
            case "GLOBAL" -> {
                sourceType = sourceTypeGlobal;
                sourceTypeArticle = sourceTypeArticleGlobal;
            }
            case "HYBRID" -> {
                sourceType = sourceTypeHybrid;
                sourceTypeArticle = sourceTypeArticleHybrid;
            }
            case "NAIVE" -> {
                sourceType = sourceTypeNaive;
                sourceTypeArticle = sourceTypeArticleNaive;
            }
            case "MIX" -> {
                sourceType = sourceTypeMix;
                sourceTypeArticle = sourceTypeArticleMix;
            }
            default -> {
                sourceType = "documents";
                sourceTypeArticle = "the ";
            }
        }

        return unifiedSystemPrompt
                .replace("{SOURCE_TYPE}", sourceType)
                .replace("{SOURCE_TYPE_ARTICLE}", sourceTypeArticle)
                .replace("{SOURCE_TYPE_UPPER}", sourceType.toUpperCase())
                .replace("{LANGUAGE}", responseLanguage);
    }

    /**
     * Initializes LightRAG instance with all storage backends.
     * Called automatically on application startup.
     */
    @PostConstruct
    public void initialize() {
        try {
            LOG.info("Initializing LightRAG service with PostgreSQL storage...");

            // Note: AGE graph database uses per-project isolation
            // Individual project graphs are created on-demand by AgeGraphStorage
            LOG.info("Apache AGE graph storage ready (per-project isolation enabled)");

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
                    4000, // maxTokens
                    topK,
                    true, // enableCache
                    kgExtractionBatchSize,
                    embeddingBatchSize,
                    entityDescriptionMaxLength,
                    entityDescriptionSeparator,
                    false // usePipelineExecutors (default: use legacy executors)
            );

            // Log configuration values for debugging
            LOG.infof(
                    "Initializing LightRAG with entity types - TEXT: %d types, CODE: %d types, CODE relationships: %d types",
                    entityTypes != null ? entityTypes.split(",").length : 0,
                    codeEntityTypes != null ? codeEntityTypes.split(",").length : 0,
                    codeRelationshipTypes != null ? codeRelationshipTypes.split(",").length : 0);

            // Resolve system prompts for each mode using the unified template
            final String localSystemPrompt = resolveSystemPromptForMode("LOCAL");
            final String globalSystemPrompt = resolveSystemPromptForMode("GLOBAL");
            final String hybridSystemPrompt = resolveSystemPromptForMode("HYBRID");
            final String naiveSystemPrompt = resolveSystemPromptForMode("NAIVE");
            final String mixSystemPrompt = resolveSystemPromptForMode("MIX");

            LOG.info("Resolved unified system prompts for all query modes");

            // Build LightRAG instance
            this.lightRAG = LightRAG.builder()
                    .config(config)
                    .llmFunction(llmAdapter)
                    .embeddingFunction(embeddingAdapter)
                    .chunkStorage(chunkKVStorage)
                    .llmCacheStorage(llmCacheStorage)
                    .chunkVectorStorage(vectorStorage)
                    .entityVectorStorage(vectorStorage)
                    .graphStorage(graphStorage)
                    .docStatusStorage(docStatusStorage)
                    .entityResolver(entityResolver)
                    .deduplicationConfig(deduplicationConfig)
                    .reranker(rerankerFactory.getReranker())
                    .codeChunker(codeChunker)
                    .codeExtractionPrompts(codeExtractionPrompts)
                    .localSystemPrompt(localSystemPrompt)
                    .globalSystemPrompt(globalSystemPrompt)
                    .hybridSystemPrompt(hybridSystemPrompt)
                    .naiveSystemPrompt(naiveSystemPrompt)
                    .mixSystemPrompt(mixSystemPrompt)
                    .bypassSystemPrompt(bypassSystemPrompt)
                    .entityExtractionSystemPrompt(entityExtractionSystemPrompt)
                    .entityTypes(entityTypes)
                    .codeEntityTypes(codeEntityTypes)
                    .codeRelationshipTypes(codeRelationshipTypes)
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
     * This processes the document through chunking, entity extraction, and graph
     * construction.
     *
     * @param documentId   The document UUID
     * @param content      The document content
     * @param fileName     The document file name
     * @param projectId    The project UUID
     * @param documentType The document type (TEXT, CODE, etc.)
     * @return CompletableFuture with the LightRAG document ID
     */
    public CompletableFuture<String> insertDocument(
            final UUID documentId,
            final String content,
            final String fileName,
            final UUID projectId,
            final br.edu.ifba.document.DocumentType documentType) {

        LOG.infof("Inserting document into LightRAG - documentId: %s, projectId: %s, type: %s",
                documentId, projectId, documentType);

        final Map<String, Object> metadata = Map.of(
                "document_id", documentId.toString(),
                "project_id", projectId.toString(),
                "filepath", fileName,
                "source_id", documentId.toString(),
                "document_type", documentType.name());

        LOG.infof("LightRAGService - Metadata created for document %s: document_type=%s",
                documentId, metadata.get("document_type"));

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
     * @param query     The query string
     * @param mode      The query mode (LOCAL, GLOBAL, HYBRID, NAIVE, MIX)
     * @param projectId The project UUID (for filtering - currently not implemented
     *                  in base LightRAG)
     * @return CompletableFuture with the query result containing answer and source
     *         chunks
     */
    public CompletableFuture<LightRAGQueryResult> query(
            final String query,
            final QueryParam.Mode mode,
            final UUID projectId) {
        return query(query, mode, projectId, null);
    }

    /**
     * Queries the LightRAG knowledge graph with optional reranking control.
     *
     * @param query        The query string
     * @param mode         The query mode (LOCAL, GLOBAL, HYBRID, NAIVE, MIX)
     * @param projectId    The project UUID (for filtering)
     * @param enableRerank Optional flag to enable/disable reranking (null uses
     *                     global config)
     * @return CompletableFuture with the query result containing answer and source
     *         chunks
     */
    public CompletableFuture<LightRAGQueryResult> query(
            final String query,
            final QueryParam.Mode mode,
            final UUID projectId,
            final Boolean enableRerank) {

        LOG.infof("Executing LightRAG query - mode: %s, projectId: %s, query: '%s', rerank: %s",
                mode, projectId, query, enableRerank);

        final QueryParam.Builder paramBuilder = QueryParam.builder()
                .mode(mode)
                .topK(topK)
                .chunkTopK(chunkTopK)
                .projectId(projectId.toString());

        // Apply rerank setting: use explicit value if provided, otherwise default to
        // true (config-based)
        if (enableRerank != null) {
            paramBuilder.enableRerank(enableRerank);
        }

        final QueryParam param = paramBuilder.build();

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
     * @param query     The query string
     * @param projectId The project UUID
     * @return CompletableFuture with the query result containing answer and source
     *         chunks
     */
    public CompletableFuture<LightRAGQueryResult> query(final String query, final UUID projectId) {
        return query(query, QueryParam.Mode.HYBRID, projectId);
    }

    /**
     * Checks if a document already has vectors stored in the database.
     * This is used to prevent duplicate processing and detect race conditions.
     *
     * @param documentId The document UUID
     * @return CompletableFuture<Boolean> true if the document has vectors, false
     *         otherwise
     */
    public CompletableFuture<Boolean> hasDocumentVectors(final UUID documentId) {
        LOG.debugf("Checking if document %s has existing vectors", documentId);
        return vectorStorage.hasVectors(documentId.toString());
    }

    /**
     * Deletes all graph entities and relations associated with a document.
     * This is called when a document is deleted to ensure proper cleanup.
     * 
     * @param projectId  The project UUID
     * @param documentId The document UUID to delete graph data for
     * @return CompletableFuture with the number of items deleted
     */
    public CompletableFuture<Integer> deleteDocumentFromGraph(final String projectId, final String documentId) {
        LOG.infof("Deleting graph data for document %s in project %s", documentId, projectId);
        return graphStorage.deleteBySourceId(projectId, documentId);
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
