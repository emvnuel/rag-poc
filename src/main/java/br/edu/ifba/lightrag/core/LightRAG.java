package br.edu.ifba.lightrag.core;

import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.query.*;
import br.edu.ifba.lightrag.rerank.Reranker;
import br.edu.ifba.lightrag.storage.DocStatusStorage;
import br.edu.ifba.lightrag.storage.DocStatusStorage.DocumentStatus;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.utils.TokenUtil;
import br.edu.ifba.shared.UuidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main LightRAG orchestrator class.
 * Coordinates document indexing, knowledge graph construction, and query execution.
 */
public class LightRAG {
    
    private static final Logger logger = LoggerFactory.getLogger(LightRAG.class);
    
    // Configuration
    private final LightRAGConfig config;
    
    // Core dependencies
    private final LLMFunction llmFunction;
    private final EmbeddingFunction embeddingFunction;
    
    // Storage backends
    private final KVStorage chunkStorage;
    private final KVStorage llmCacheStorage;
    private final VectorStorage chunkVectorStorage;
    private final VectorStorage entityVectorStorage;
    private final GraphStorage graphStorage;
    private final DocStatusStorage docStatusStorage;
    
    // Entity deduplication (optional)
    private final EntityResolver entityResolver;
    private final DeduplicationConfig deduplicationConfig;
    
    // Reranker (optional)
    private final Reranker reranker;
    
    // System prompts for query modes
    private final String localSystemPrompt;
    private final String globalSystemPrompt;
    private final String hybridSystemPrompt;
    private final String naiveSystemPrompt;
    private final String mixSystemPrompt;
    private final String bypassSystemPrompt;
    
    // System prompt for entity extraction
    private final String entityExtractionSystemPrompt;
    
    // Entity extraction configuration
    private final String entityTypes;
    private final String extractionLanguage;
    private final String entityExtractionUserPrompt;
    
    // Extraction configuration for gleaning and other enhancements (optional)
    private final LightRAGExtractionConfig extractionConfig;
    
    // Initialization flag
    private volatile boolean initialized = false;
    
    // Query executors (initialized lazily)
    private volatile LocalQueryExecutor localExecutor;
    private volatile GlobalQueryExecutor globalExecutor;
    private volatile HybridQueryExecutor hybridExecutor;
    private volatile NaiveQueryExecutor naiveExecutor;
    private volatile MixQueryExecutor mixExecutor;
    
    /**
     * Creates a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for LightRAG instances.
     */
    public static class Builder {
        private LightRAGConfig config = LightRAGConfig.defaults();
        private LLMFunction llmFunction;
        private EmbeddingFunction embeddingFunction;
        private KVStorage chunkStorage;
        private KVStorage llmCacheStorage;
        private VectorStorage chunkVectorStorage;
        private VectorStorage entityVectorStorage;
        private GraphStorage graphStorage;
        private DocStatusStorage docStatusStorage;
        private String localSystemPrompt;
        private String globalSystemPrompt;
        private String hybridSystemPrompt;
        private String naiveSystemPrompt;
        private String mixSystemPrompt;
        private String bypassSystemPrompt;
        private String entityExtractionSystemPrompt;
        private String entityTypes;
        private String extractionLanguage;
        private String entityExtractionUserPrompt;
        private EntityResolver entityResolver;
        private DeduplicationConfig deduplicationConfig;
        private LightRAGExtractionConfig extractionConfig;
        private Reranker reranker;
        
        public Builder config(@NotNull LightRAGConfig config) {
            this.config = config;
            return this;
        }
        
        public Builder llmFunction(@NotNull LLMFunction llmFunction) {
            this.llmFunction = llmFunction;
            return this;
        }
        
        public Builder embeddingFunction(@NotNull EmbeddingFunction embeddingFunction) {
            this.embeddingFunction = embeddingFunction;
            return this;
        }
        
        public Builder chunkStorage(@NotNull KVStorage chunkStorage) {
            this.chunkStorage = chunkStorage;
            return this;
        }
        
        public Builder llmCacheStorage(@NotNull KVStorage llmCacheStorage) {
            this.llmCacheStorage = llmCacheStorage;
            return this;
        }
        
        public Builder chunkVectorStorage(@NotNull VectorStorage chunkVectorStorage) {
            this.chunkVectorStorage = chunkVectorStorage;
            return this;
        }
        
        public Builder entityVectorStorage(@NotNull VectorStorage entityVectorStorage) {
            this.entityVectorStorage = entityVectorStorage;
            return this;
        }
        
        public Builder graphStorage(@NotNull GraphStorage graphStorage) {
            this.graphStorage = graphStorage;
            return this;
        }
        
        public Builder docStatusStorage(@NotNull DocStatusStorage docStatusStorage) {
            this.docStatusStorage = docStatusStorage;
            return this;
        }
        
        public Builder localSystemPrompt(@NotNull String localSystemPrompt) {
            this.localSystemPrompt = localSystemPrompt;
            return this;
        }
        
        public Builder globalSystemPrompt(@NotNull String globalSystemPrompt) {
            this.globalSystemPrompt = globalSystemPrompt;
            return this;
        }
        
        public Builder hybridSystemPrompt(@NotNull String hybridSystemPrompt) {
            this.hybridSystemPrompt = hybridSystemPrompt;
            return this;
        }
        
        public Builder naiveSystemPrompt(@NotNull String naiveSystemPrompt) {
            this.naiveSystemPrompt = naiveSystemPrompt;
            return this;
        }
        
        public Builder mixSystemPrompt(@NotNull String mixSystemPrompt) {
            this.mixSystemPrompt = mixSystemPrompt;
            return this;
        }
        
        public Builder bypassSystemPrompt(@NotNull String bypassSystemPrompt) {
            this.bypassSystemPrompt = bypassSystemPrompt;
            return this;
        }
        
        public Builder entityExtractionSystemPrompt(@NotNull String entityExtractionSystemPrompt) {
            this.entityExtractionSystemPrompt = entityExtractionSystemPrompt;
            return this;
        }
        
        public Builder entityTypes(@NotNull String entityTypes) {
            this.entityTypes = entityTypes;
            return this;
        }
        
        public Builder extractionLanguage(@NotNull String extractionLanguage) {
            this.extractionLanguage = extractionLanguage;
            return this;
        }
        
        public Builder entityExtractionUserPrompt(@NotNull String entityExtractionUserPrompt) {
            this.entityExtractionUserPrompt = entityExtractionUserPrompt;
            return this;
        }
        
        public Builder entityResolver(@Nullable EntityResolver entityResolver) {
            this.entityResolver = entityResolver;
            return this;
        }
        
        public Builder deduplicationConfig(@Nullable DeduplicationConfig deduplicationConfig) {
            this.deduplicationConfig = deduplicationConfig;
            return this;
        }
        
        public Builder extractionConfig(@Nullable LightRAGExtractionConfig extractionConfig) {
            this.extractionConfig = extractionConfig;
            return this;
        }
        
        public Builder reranker(@Nullable Reranker reranker) {
            this.reranker = reranker;
            return this;
        }
        
        public LightRAG build() {
            if (llmFunction == null) {
                throw new IllegalStateException("llmFunction is required");
            }
            if (embeddingFunction == null) {
                throw new IllegalStateException("embeddingFunction is required");
            }
            if (chunkStorage == null) {
                throw new IllegalStateException("chunkStorage is required");
            }
            if (llmCacheStorage == null) {
                throw new IllegalStateException("llmCacheStorage is required");
            }
            if (chunkVectorStorage == null) {
                throw new IllegalStateException("chunkVectorStorage is required");
            }
            if (entityVectorStorage == null) {
                throw new IllegalStateException("entityVectorStorage is required");
            }
            if (graphStorage == null) {
                throw new IllegalStateException("graphStorage is required");
            }
            if (docStatusStorage == null) {
                throw new IllegalStateException("docStatusStorage is required");
            }
            if (localSystemPrompt == null) {
                throw new IllegalStateException("localSystemPrompt is required");
            }
            if (globalSystemPrompt == null) {
                throw new IllegalStateException("globalSystemPrompt is required");
            }
            if (hybridSystemPrompt == null) {
                throw new IllegalStateException("hybridSystemPrompt is required");
            }
            if (naiveSystemPrompt == null) {
                throw new IllegalStateException("naiveSystemPrompt is required");
            }
            if (mixSystemPrompt == null) {
                throw new IllegalStateException("mixSystemPrompt is required");
            }
            if (bypassSystemPrompt == null) {
                throw new IllegalStateException("bypassSystemPrompt is required");
            }
            if (entityExtractionSystemPrompt == null) {
                throw new IllegalStateException("entityExtractionSystemPrompt is required");
            }
            if (entityTypes == null) {
                throw new IllegalStateException("entityTypes is required");
            }
            if (extractionLanguage == null) {
                throw new IllegalStateException("extractionLanguage is required");
            }
            if (entityExtractionUserPrompt == null) {
                throw new IllegalStateException("entityExtractionUserPrompt is required");
            }
            
            return new LightRAG(
                config,
                llmFunction,
                embeddingFunction,
                chunkStorage,
                llmCacheStorage,
                chunkVectorStorage,
                entityVectorStorage,
                graphStorage,
                docStatusStorage,
                localSystemPrompt,
                globalSystemPrompt,
                hybridSystemPrompt,
                naiveSystemPrompt,
                mixSystemPrompt,
                bypassSystemPrompt,
                entityExtractionSystemPrompt,
                entityTypes,
                extractionLanguage,
                entityExtractionUserPrompt,
                entityResolver,
                deduplicationConfig,
                extractionConfig,
                reranker
            );
        }
    }
    
    /**
     * Private constructor - use Builder instead.
     */
    private LightRAG(
        @NotNull LightRAGConfig config,
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull KVStorage llmCacheStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage,
        @NotNull DocStatusStorage docStatusStorage,
        @NotNull String localSystemPrompt,
        @NotNull String globalSystemPrompt,
        @NotNull String hybridSystemPrompt,
        @NotNull String naiveSystemPrompt,
        @NotNull String mixSystemPrompt,
        @NotNull String bypassSystemPrompt,
        @NotNull String entityExtractionSystemPrompt,
        @NotNull String entityTypes,
        @NotNull String extractionLanguage,
        @NotNull String entityExtractionUserPrompt,
        @Nullable EntityResolver entityResolver,
        @Nullable DeduplicationConfig deduplicationConfig,
        @Nullable LightRAGExtractionConfig extractionConfig,
        @Nullable Reranker reranker
    ) {
        this.config = config;
        this.llmFunction = llmFunction;
        this.embeddingFunction = embeddingFunction;
        this.chunkStorage = chunkStorage;
        this.llmCacheStorage = llmCacheStorage;
        this.chunkVectorStorage = chunkVectorStorage;
        this.entityVectorStorage = entityVectorStorage;
        this.graphStorage = graphStorage;
        this.docStatusStorage = docStatusStorage;
        this.entityResolver = entityResolver;
        this.deduplicationConfig = deduplicationConfig;
        this.extractionConfig = extractionConfig;
        this.reranker = reranker;
        this.localSystemPrompt = localSystemPrompt;
        this.globalSystemPrompt = globalSystemPrompt;
        this.hybridSystemPrompt = hybridSystemPrompt;
        this.naiveSystemPrompt = naiveSystemPrompt;
        this.mixSystemPrompt = mixSystemPrompt;
        this.bypassSystemPrompt = bypassSystemPrompt;
        this.entityExtractionSystemPrompt = entityExtractionSystemPrompt;
        this.entityTypes = entityTypes;
        this.extractionLanguage = extractionLanguage;
        this.entityExtractionUserPrompt = entityExtractionUserPrompt;
    }
    
    /**
     * Initializes the LightRAG instance.
     * Must be called before any insert or query operations.
     */
    public CompletableFuture<Void> initialize() {
        if (initialized) {
            return CompletableFuture.completedFuture(null);
        }
        
        logger.info("Initializing LightRAG...");
        
        // Initialize all storage backends in parallel
        return CompletableFuture.allOf(
            chunkStorage.initialize(),
            llmCacheStorage.initialize(),
            chunkVectorStorage.initialize(),
            entityVectorStorage.initialize(),
            graphStorage.initialize(),
            docStatusStorage.initialize()
        ).thenRun(() -> {
            // Initialize query executors
            this.localExecutor = new LocalQueryExecutor(
                llmFunction, embeddingFunction, chunkStorage, 
                chunkVectorStorage, entityVectorStorage, graphStorage,
                localSystemPrompt
            );
            this.globalExecutor = new GlobalQueryExecutor(
                llmFunction, embeddingFunction, chunkStorage,
                chunkVectorStorage, entityVectorStorage, graphStorage,
                globalSystemPrompt
            );
            this.hybridExecutor = new HybridQueryExecutor(
                llmFunction, embeddingFunction, chunkStorage,
                chunkVectorStorage, entityVectorStorage, graphStorage,
                localSystemPrompt, globalSystemPrompt, hybridSystemPrompt
            );
            this.naiveExecutor = new NaiveQueryExecutor(
                llmFunction, embeddingFunction, chunkStorage,
                chunkVectorStorage, entityVectorStorage, graphStorage,
                naiveSystemPrompt
            );
            this.mixExecutor = new MixQueryExecutor(
                llmFunction, embeddingFunction, chunkStorage,
                chunkVectorStorage, entityVectorStorage, graphStorage,
                mixSystemPrompt, reranker
            );
            
            initialized = true;
            logger.info("LightRAG initialized successfully");
        });
    }
    
    /**
     * Inserts a single document with metadata.
     *
     * @param content The document content
     * @param metadata Optional metadata (e.g., filepath, source information)
     * @return CompletableFuture with the document ID
     */
    public CompletableFuture<String> insert(
        @NotNull String content,
        @Nullable Map<String, Object> metadata
    ) {
        String docId = UUID.randomUUID().toString();
        return insertWithId(docId, content, metadata);
    }
    
    /**
     * Inserts a single document with a specific document ID.
     * If the document is already completed or processing, returns the existing docId without reprocessing.
     *
     * @param docId The document ID to use
     * @param content The document content
     * @param metadata Optional metadata (e.g., filepath, source information)
     * @return CompletableFuture with the document ID
     */
    public CompletableFuture<String> insertWithId(
        @NotNull String docId,
        @NotNull String content,
        @Nullable Map<String, Object> metadata
    ) {
        ensureInitialized();
        
        String filePath = metadata != null ? (String) metadata.get("filepath") : null;
        logger.info("Inserting document with ID: {}", docId);
        
        // Check if document is already processed or being processed
        return docStatusStorage.getStatus(docId)
            .thenCompose(existingStatus -> {
                if (existingStatus != null) {
                    if (existingStatus.processingStatus() == DocStatusStorage.ProcessingStatus.COMPLETED) {
                        logger.info("Document {} already completed, skipping reprocessing", docId);
                        return CompletableFuture.completedFuture(docId);
                    } else if (existingStatus.processingStatus() == DocStatusStorage.ProcessingStatus.PROCESSING) {
                        logger.warn("Document {} is already being processed, skipping duplicate processing attempt", docId);
                        return CompletableFuture.completedFuture(docId);
                    }
                }
                
                // Create initial status
                DocumentStatus pendingStatus = DocumentStatus.pending(docId, filePath);
                DocumentStatus processingStatus = pendingStatus.asProcessing();
                
                return docStatusStorage.setStatus(processingStatus)
                    .thenCompose(v -> processDocument(docId, content, metadata))
                    .thenCompose(result -> {
                        // Update status to completed
                        DocumentStatus completedStatus = processingStatus.asCompleted(
                            result.chunkCount,
                            result.entityCount,
                            result.relationCount
                        );
                        return docStatusStorage.setStatus(completedStatus)
                            .thenApply(v -> docId);
                    })
                    .exceptionally(ex -> {
                        logger.error("Failed to insert document: {}", docId, ex);
                        DocumentStatus failedStatus = processingStatus.asFailed(ex.getMessage());
                        docStatusStorage.setStatus(failedStatus).join();
                        throw new RuntimeException("Document insertion failed", ex);
                    });
            });
    }
    
    /**
     * Inserts a single document without metadata.
     * Convenience method for simple insertions.
     *
     * @param content The document content
     * @return CompletableFuture with the document ID
     */
    public CompletableFuture<String> insert(@NotNull String content) {
        return insert(content, null);
    }
    
    /**
     * Inserts multiple documents in batch.
     *
     * @param documents List of document contents
     * @return CompletableFuture with list of document IDs
     */
    public CompletableFuture<List<String>> insertBatch(@NotNull List<String> documents) {
        ensureInitialized();
        logger.info("Inserting {} documents in batch", documents.size());
        
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String content : documents) {
            futures.add(insert(content, null));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList());
    }
    
    /**
     * Queries the knowledge graph.
     *
     * @param query The query string
     * @param param Query parameters (mode, top_k, etc.)
     * @return CompletableFuture with the query result containing answer and source chunks
     */
    public CompletableFuture<LightRAGQueryResult> query(
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        ensureInitialized();
        logger.info("Executing query with mode: {}", param.getMode());
        
        // Dispatch to appropriate query executor based on mode
        return switch (param.getMode()) {
            case LOCAL -> executeLocalQuery(query, param);
            case GLOBAL -> executeGlobalQuery(query, param);
            case HYBRID -> executeHybridQuery(query, param);
            case NAIVE -> executeNaiveQuery(query, param);
            case MIX -> executeMixQuery(query, param);
            case BYPASS -> executeBypassQuery(query, param);
        };
    }
    
    /**
     * Processing result for internal tracking.
     */
    private record ProcessingResult(int chunkCount, int entityCount, int relationCount) {}
    
    /**
     * Processes a single document: chunking, extraction, and graph construction.
     */
    private CompletableFuture<ProcessingResult> processDocument(
        @NotNull String docId,
        @NotNull String content,
        @Nullable Map<String, Object> metadata
    ) {
        // Step 1: Chunk the document
        List<String> chunks = TokenUtil.chunkText(
            content,
            config.chunkSize(),
            config.chunkOverlap()
        );
        
        logger.info("Document {} chunked into {} pieces", docId, chunks.size());
        
        // Step 2: Store chunks in KV storage
        List<String> chunkIds = new ArrayList<>();
        List<CompletableFuture<Void>> storageFutures = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = UuidUtils.randomV7().toString();
            chunkIds.add(chunkId);
            storageFutures.add(chunkStorage.set(chunkId, chunks.get(i)));
        }
        
        // Step 3: Wait for storage, then generate embeddings in batches
        return CompletableFuture.allOf(storageFutures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                logger.debug("Chunks stored, generating embeddings for {} chunks in batches", chunks.size());
                
                // Batch embedding requests to reduce API calls
                int embeddingBatchSize = config.embeddingBatchSize();
                List<CompletableFuture<Void>> embeddingBatchFutures = new ArrayList<>();
                List<VectorStorage.VectorEntry> vectorEntries = new ArrayList<>();
                
                for (int batchStart = 0; batchStart < chunks.size(); batchStart += embeddingBatchSize) {
                    int batchEnd = Math.min(batchStart + embeddingBatchSize, chunks.size());
                    final int batchIndex = batchStart / embeddingBatchSize + 1;
                    final int totalBatches = (chunks.size() + embeddingBatchSize - 1) / embeddingBatchSize;
                    final int finalBatchStart = batchStart;
                    
                    List<String> batchChunks = chunks.subList(batchStart, batchEnd);
                    logger.debug("Processing embedding batch {}/{} ({} chunks)", 
                                 batchIndex, totalBatches, batchChunks.size());
                    
                    // Generate embeddings for this batch
                    CompletableFuture<Void> batchFuture = embeddingFunction.embed(batchChunks)
                        .thenAccept(embeddings -> {
                            // Create vector entries for this batch
                            String documentId = metadata != null ? (String) metadata.get("document_id") : null;
                            String projectId = metadata != null ? (String) metadata.get("project_id") : null;
                            
                            for (int i = 0; i < embeddings.size(); i++) {
                                int chunkIndex = finalBatchStart + i;
                                VectorStorage.VectorMetadata vectorMetadata = new VectorStorage.VectorMetadata(
                                    "chunk",
                                    batchChunks.get(i),
                                    documentId,  // documentId (UUID from the document table)
                                    chunkIndex,
                                    projectId  // projectId (UUID from the project table)
                                );
                                synchronized (vectorEntries) {
                                    vectorEntries.add(new VectorStorage.VectorEntry(
                                        chunkIds.get(chunkIndex), 
                                        embeddings.get(i), 
                                        vectorMetadata
                                    ));
                                }
                            }
                            logger.debug("Embedding batch {}/{} completed", batchIndex, totalBatches);
                        });
                    
                    embeddingBatchFutures.add(batchFuture);
                }
                
                // Wait for all embedding batches, then upsert vectors
                return CompletableFuture.allOf(embeddingBatchFutures.toArray(new CompletableFuture[0]))
                    .thenCompose(ignored -> {
                        logger.info("All embeddings generated, upserting {} vectors to storage", vectorEntries.size());
                        return chunkVectorStorage.upsertBatch(vectorEntries);
                    });
            })
            .thenCompose(v -> extractKnowledgeGraph(docId, chunks, metadata))
            .thenApply(kgResult -> new ProcessingResult(chunks.size(), kgResult.entityCount, kgResult.relationCount));
    }
    
    /**
     * Knowledge graph extraction result.
     */
    private record KGExtractionResult(int entityCount, int relationCount) {}
    
    /**
     * Extracts entities and relations from chunks using LLM.
     * This implementation:
     * 1. Builds extraction prompts for each chunk
     * 2. Calls LLM to extract entities and relations in batches
     * 3. Parses JSON responses into Entity/Relation objects
     * 4. Upserts entities and relations to graph storage
     * 5. Generates and stores entity embeddings in vector storage
     */
    private CompletableFuture<KGExtractionResult> extractKnowledgeGraph(
        @NotNull String docId,
        @NotNull List<String> chunks,
        @Nullable Map<String, Object> metadata
    ) {
        logger.info("Extracting knowledge graph from {} chunks", chunks.size());
        
        if (chunks.isEmpty()) {
            return CompletableFuture.completedFuture(new KGExtractionResult(0, 0));
        }
        
        // Process chunks in batches to control parallelism
        int kgBatchSize = config.kgExtractionBatchSize();
        List<Entity> allEntities = new ArrayList<>();
        List<Relation> allRelations = new ArrayList<>();
        
        // Split chunks into batches
        int totalBatches = (chunks.size() + kgBatchSize - 1) / kgBatchSize;
        logger.info("Processing {} chunks in {} batches (batch size: {})", 
                     chunks.size(), totalBatches, kgBatchSize);
        
        // Process batches sequentially using CompletableFuture chain
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        
        for (int batchStart = 0; batchStart < chunks.size(); batchStart += kgBatchSize) {
            int batchEnd = Math.min(batchStart + kgBatchSize, chunks.size());
            final int batchIndex = batchStart / kgBatchSize + 1;
            final int finalBatchStart = batchStart;
            
            List<String> batchChunks = chunks.subList(batchStart, batchEnd);
            
            chain = chain.thenCompose(v -> {
                logger.info("Processing KG extraction batch {}/{} ({} chunks)", 
                           batchIndex, totalBatches, batchChunks.size());
                
                // Process all chunks in this batch in parallel
                List<CompletableFuture<KGExtractionChunkResult>> batchFutures = new ArrayList<>();
                
                for (int i = 0; i < batchChunks.size(); i++) {
                    String chunkId = UuidUtils.randomV7().toString();
                    String chunkContent = batchChunks.get(i);
                    batchFutures.add(extractKnowledgeGraphFromChunk(chunkId, chunkContent));
                }
                
                // Wait for this batch to complete before moving to next batch
                return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                    .thenCompose(ignored -> {
                        // Collect results from THIS batch only (not accumulating in memory)
                        List<Entity> batchEntities = new ArrayList<>();
                        List<Relation> batchRelations = new ArrayList<>();
                        
                        for (CompletableFuture<KGExtractionChunkResult> future : batchFutures) {
                            KGExtractionChunkResult result = future.join();
                            batchEntities.addAll(result.entities);
                            batchRelations.addAll(result.relations);
                        }
                        
                        logger.info("KG batch {}/{} extracted - entities: {}, relations: {}", 
                                   batchIndex, totalBatches, batchEntities.size(), batchRelations.size());
                        
                        // STORE THIS BATCH IMMEDIATELY (store-as-you-go strategy)
                        // Benefits: constant memory, crash resilience, progressive persistence
                        return storeKnowledgeGraph(batchEntities, batchRelations, metadata)
                            .thenApply(stored -> {
                                // Track cumulative totals for final result (minimal memory - just counts)
                                synchronized (allEntities) {
                                    allEntities.addAll(batchEntities);
                                    allRelations.addAll(batchRelations);
                                }
                                
                                logger.info("KG batch {}/{} stored successfully - cumulative total: {} entities, {} relations",
                                           batchIndex, totalBatches, allEntities.size(), allRelations.size());
                                
                                return (Void) null;
                            })
                            .exceptionally(ex -> {
                                logger.error("Failed to store KG batch {}/{}: {}", 
                                           batchIndex, totalBatches, ex.getMessage(), ex);
                                throw new RuntimeException("KG batch storage failed", ex);
                            });
                    });
            });
        }
        
        // After all batches complete, return the cumulative result
        // Note: Storage happens per-batch (store-as-you-go), so no final storage needed
        return chain.thenApply(v -> {
            logger.info("All KG extraction and storage completed - total entities: {}, relations: {}", 
                       allEntities.size(), allRelations.size());
            
            return new KGExtractionResult(allEntities.size(), allRelations.size());
        });
    }
    
    /**
     * Result of KG extraction from a single chunk.
     */
    private record KGExtractionChunkResult(List<Entity> entities, List<Relation> relations) {}
    
    /**
     * Extracts entities and relations from a single chunk using LLM.
     * 
     * <p>This method implements iterative gleaning (T006) per official LightRAG:
     * <ol>
     *   <li>Initial extraction pass</li>
     *   <li>If gleaning enabled, additional passes to capture missed entities</li>
     *   <li>Merge results from all passes (deduplicate by entity name)</li>
     *   <li>Stop early if gleaning returns no new entities</li>
     * </ol></p>
     * 
     * @param chunkId unique identifier for the chunk
     * @param chunkContent the text content to extract from
     * @return combined extraction results from all passes
     */
    private CompletableFuture<KGExtractionChunkResult> extractKnowledgeGraphFromChunk(
        @NotNull String chunkId,
        @NotNull String chunkContent
    ) {
        // Fill placeholders in the system prompt template
        String filledSystemPrompt = fillEntityExtractionPromptTemplate(chunkContent);
        
        // Use configured user prompt from .env
        String userPrompt = entityExtractionUserPrompt;
        
        // Initial extraction pass
        return llmFunction.apply(userPrompt, filledSystemPrompt)
            .thenCompose(response -> {
                KGExtractionChunkResult initialResult = parseKGExtractionResponse(chunkId, response);
                
                // Check if gleaning is enabled and max passes > 0
                if (extractionConfig == null || !extractionConfig.gleaning().enabled() || extractionConfig.gleaning().maxPasses() <= 0) {
                    logger.debug("Gleaning disabled for chunk {}, returning initial extraction: {} entities, {} relations", 
                        chunkId, initialResult.entities().size(), initialResult.relations().size());
                    return CompletableFuture.completedFuture(initialResult);
                }
                
                // Run gleaning passes
                return runGleaningPasses(chunkId, chunkContent, response, initialResult);
            })
            .exceptionally(e -> {
                logger.warn("Failed to extract KG from chunk {}: {}", chunkId, e.getMessage());
                return new KGExtractionChunkResult(List.of(), List.of());
            });
    }
    
    /**
     * Runs iterative gleaning passes to capture entities missed in initial extraction.
     * 
     * <p>Per official LightRAG implementation:
     * <ul>
     *   <li>Each gleaning pass prompts for "many entities were missed"</li>
     *   <li>Results are merged with previous passes (deduplicate by entity name)</li>
     *   <li>Stops early if a pass returns no new entities</li>
     * </ul></p>
     * 
     * @param chunkId chunk identifier for logging
     * @param chunkContent original chunk text
     * @param previousResponse the response from the previous extraction pass
     * @param accumulatedResult entities/relations accumulated so far
     * @return combined result from all passes
     */
    private CompletableFuture<KGExtractionChunkResult> runGleaningPasses(
        @NotNull String chunkId,
        @NotNull String chunkContent,
        @NotNull String previousResponse,
        @NotNull KGExtractionChunkResult accumulatedResult
    ) {
        int maxPasses = extractionConfig.gleaning().maxPasses();
        
        // Build recursive gleaning chain
        return runGleaningPassRecursive(chunkId, chunkContent, previousResponse, accumulatedResult, 1, maxPasses);
    }
    
    /**
     * Recursive helper for gleaning passes.
     */
    private CompletableFuture<KGExtractionChunkResult> runGleaningPassRecursive(
        @NotNull String chunkId,
        @NotNull String chunkContent,
        @NotNull String previousResponse,
        @NotNull KGExtractionChunkResult accumulatedResult,
        int currentPass,
        int maxPasses
    ) {
        if (currentPass > maxPasses) {
            logger.debug("Gleaning complete for chunk {}: {} total entities, {} total relations after {} passes", 
                chunkId, accumulatedResult.entities().size(), accumulatedResult.relations().size(), maxPasses);
            return CompletableFuture.completedFuture(accumulatedResult);
        }
        
        logger.debug("Starting gleaning pass {}/{} for chunk {}", currentPass, maxPasses, chunkId);
        
        // Build gleaning prompt with context of what was already extracted
        String gleaningPrompt = buildGleaningPrompt(chunkContent, previousResponse);
        
        return llmFunction.apply(GLEANING_USER_PROMPT, gleaningPrompt)
            .thenCompose(gleaningResponse -> {
                KGExtractionChunkResult gleaningResult = parseKGExtractionResponse(chunkId, gleaningResponse);
                
                // Check if gleaning found any new entities
                int newEntitiesFound = countNewEntities(accumulatedResult.entities(), gleaningResult.entities());
                int newRelationsFound = countNewRelations(accumulatedResult.relations(), gleaningResult.relations());
                
                logger.debug("Gleaning pass {} found {} new entities, {} new relations for chunk {}", 
                    currentPass, newEntitiesFound, newRelationsFound, chunkId);
                
                // Early stop if no new entities/relations found
                if (newEntitiesFound == 0 && newRelationsFound == 0) {
                    logger.debug("Early stopping gleaning for chunk {} - no new entities/relations in pass {}", 
                        chunkId, currentPass);
                    return CompletableFuture.completedFuture(accumulatedResult);
                }
                
                // Merge results
                KGExtractionChunkResult mergedResult = mergeExtractionResults(accumulatedResult, gleaningResult);
                
                // Continue to next pass
                return runGleaningPassRecursive(chunkId, chunkContent, gleaningResponse, mergedResult, currentPass + 1, maxPasses);
            })
            .exceptionally(e -> {
                logger.warn("Gleaning pass {} failed for chunk {}: {}", currentPass, chunkId, e.getMessage());
                // Return what we have so far on error
                return accumulatedResult;
            });
    }
    
    /**
     * Builds the gleaning prompt that asks the LLM to find missed entities.
     * Includes the original chunk content and previous extraction for context.
     */
    private String buildGleaningPrompt(@NotNull String chunkContent, @NotNull String previousResponse) {
        return String.format(GLEANING_SYSTEM_PROMPT_TEMPLATE, 
            entityTypes, 
            extractionLanguage, 
            chunkContent, 
            previousResponse);
    }
    
    /**
     * Counts new entities not present in the accumulated list.
     */
    private int countNewEntities(List<Entity> accumulated, List<Entity> newResults) {
        java.util.Set<String> existingNames = accumulated.stream()
            .map(e -> e.getEntityName().toLowerCase())
            .collect(java.util.stream.Collectors.toSet());
        
        return (int) newResults.stream()
            .filter(e -> !existingNames.contains(e.getEntityName().toLowerCase()))
            .count();
    }
    
    /**
     * Counts new relations not present in the accumulated list.
     */
    private int countNewRelations(List<Relation> accumulated, List<Relation> newResults) {
        java.util.Set<String> existingKeys = accumulated.stream()
            .map(r -> r.getSrcId().toLowerCase() + "->" + r.getTgtId().toLowerCase())
            .collect(java.util.stream.Collectors.toSet());
        
        return (int) newResults.stream()
            .filter(r -> !existingKeys.contains(r.getSrcId().toLowerCase() + "->" + r.getTgtId().toLowerCase()))
            .count();
    }
    
    /**
     * Merges gleaning results with accumulated results, deduplicating by entity name.
     */
    private KGExtractionChunkResult mergeExtractionResults(
        @NotNull KGExtractionChunkResult accumulated, 
        @NotNull KGExtractionChunkResult newResults
    ) {
        // Merge entities (deduplicate by name, keeping first occurrence)
        Map<String, Entity> entityMap = new HashMap<>();
        for (Entity e : accumulated.entities()) {
            entityMap.putIfAbsent(e.getEntityName().toLowerCase(), e);
        }
        for (Entity e : newResults.entities()) {
            entityMap.putIfAbsent(e.getEntityName().toLowerCase(), e);
        }
        
        // Merge relations (deduplicate by src->tgt pair)
        Map<String, Relation> relationMap = new HashMap<>();
        for (Relation r : accumulated.relations()) {
            String key = r.getSrcId().toLowerCase() + "->" + r.getTgtId().toLowerCase();
            relationMap.putIfAbsent(key, r);
        }
        for (Relation r : newResults.relations()) {
            String key = r.getSrcId().toLowerCase() + "->" + r.getTgtId().toLowerCase();
            relationMap.putIfAbsent(key, r);
        }
        
        return new KGExtractionChunkResult(
            new ArrayList<>(entityMap.values()),
            new ArrayList<>(relationMap.values())
        );
    }
    
    /**
     * Gleaning system prompt template.
     * Asks the LLM to find entities/relations missed in the initial extraction.
     * Placeholders: entity_types, language, input_text, previous_extraction
     */
    private static final String GLEANING_SYSTEM_PROMPT_TEMPLATE = """
        -Goal-
        Given a text document that was previously analyzed for entity and relation extraction, \
        many entities and relations were missed in the first analysis. \
        Please identify and add any ADDITIONAL entities and relations that were missed.
        
        -Entity Types-
        %s
        
        -Language-
        Output must be in %s.
        
        -Original Text-
        %s
        
        -Previous Extraction Results-
        %s
        
        -Instructions-
        1. Review the original text and the previous extraction results
        2. Identify any entities and relations that were MISSED in the previous extraction
        3. DO NOT repeat entities or relations that were already extracted
        4. Use the same output format as the original extraction:
           - entity{tuple_delimiter}entity_name{tuple_delimiter}entity_type{tuple_delimiter}entity_description
           - relation{tuple_delimiter}src_id{tuple_delimiter}tgt_id{tuple_delimiter}relationship_keywords{tuple_delimiter}relationship_description
        5. End with {completion_delimiter}
        
        Add the missed entities and relations below:
        """;
    
    /**
     * User prompt for gleaning passes.
     */
    private static final String GLEANING_USER_PROMPT = 
        "MANY ENTITIES AND RELATIONS WERE MISSED IN THE PREVIOUS EXTRACTION. " +
        "Please add them below using the same format. Only include NEW items not already extracted.";
    
    /**
     * Fills template placeholders in the entity extraction system prompt.
     * Replaces {input_text}, {entity_types}, and {language} with actual values from .env configuration.
     */
    private String fillEntityExtractionPromptTemplate(@NotNull String inputText) {
        // Replace placeholders with values from .env configuration
        return entityExtractionSystemPrompt
            .replace("{input_text}", inputText)
            .replace("{entity_types}", entityTypes)
            .replace("{language}", extractionLanguage);
    }
    
    /**
     * Parses LLM response into entities and relations.
     * Expects LightRAG tuple-delimiter format:
     * - entity{tuple_delimiter}entity_name{tuple_delimiter}entity_type{tuple_delimiter}entity_description
     * - relation{tuple_delimiter}src_id{tuple_delimiter}tgt_id{tuple_delimiter}relationship_keywords{tuple_delimiter}relationship_description
     */
    private KGExtractionChunkResult parseKGExtractionResponse(
        @NotNull String chunkId,
        @NotNull String response
    ) {
        try {
            List<Entity> entities = new ArrayList<>();
            List<Relation> relations = new ArrayList<>();
            
            // Parse line-by-line for tuple-delimiter format
            String[] lines = response.split("\n");
            
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                // Parse entity lines: entity{tuple_delimiter}name{tuple_delimiter}type{tuple_delimiter}description
                if (line.startsWith("entity{tuple_delimiter}")) {
                    Entity entity = parseEntityLine(line, chunkId);
                    if (entity != null) {
                        entities.add(entity);
                    }
                }
                // Parse relation lines: relation{tuple_delimiter}src{tuple_delimiter}tgt{tuple_delimiter}keywords{tuple_delimiter}description
                else if (line.startsWith("relation{tuple_delimiter}")) {
                    Relation relation = parseRelationLine(line, chunkId);
                    if (relation != null) {
                        relations.add(relation);
                    }
                }
                // Stop at completion delimiter
                else if (line.contains("{completion_delimiter}")) {
                    break;
                }
            }
            
            logger.debug("Parsed {} entities and {} relations from chunk {}", 
                        entities.size(), relations.size(), chunkId);
            
            return new KGExtractionChunkResult(entities, relations);
            
        } catch (Exception e) {
            logger.warn("Failed to parse KG extraction response for chunk {}: {}", chunkId, e.getMessage());
            return new KGExtractionChunkResult(List.of(), List.of());
        }
    }
    
    /**
     * Parses a single entity line in tuple-delimiter format.
     * Format: entity{tuple_delimiter}entity_name{tuple_delimiter}entity_type{tuple_delimiter}entity_description
     */
    private Entity parseEntityLine(@NotNull String line, @NotNull String sourceChunkId) {
        try {
            // Split by {tuple_delimiter}
            String[] parts = line.split("\\{tuple_delimiter\\}");
            
            if (parts.length < 4) {
                logger.debug("Invalid entity line format (expected 4 parts, got {}): {}", parts.length, line);
                return null;
            }
            
            // parts[0] = "entity" (already validated by caller)
            String entityName = normalizeEntityName(parts[1].trim());
            String entityType = parts[2].trim();
            String description = parts.length > 3 ? parts[3].trim() : "";
            
            if (entityName.isEmpty()) {
                return null;
            }
            
            return Entity.builder()
                .entityName(entityName)
                .entityType(entityType.isEmpty() ? "CONCEPT" : entityType)
                .description(description)
                .build();
                
        } catch (Exception e) {
            logger.debug("Failed to parse entity line: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Parses a single relation line in tuple-delimiter format.
     * Format: relation{tuple_delimiter}src_id{tuple_delimiter}tgt_id{tuple_delimiter}relationship_keywords{tuple_delimiter}relationship_description
     * 
     * <p>Validates and filters:</p>
     * <ul>
     *   <li>Normalizes entity names (removes quotes, trims whitespace)</li>
     *   <li>Rejects self-referential relations (source equals target)</li>
     * </ul>
     */
    private Relation parseRelationLine(@NotNull String line, @NotNull String sourceChunkId) {
        try {
            // Split by {tuple_delimiter}
            String[] parts = line.split("\\{tuple_delimiter\\}");
            
            if (parts.length < 5) {
                logger.debug("Invalid relation line format (expected 5 parts, got {}): {}", parts.length, line);
                return null;
            }
            
            // parts[0] = "relation" (already validated by caller)
            String srcId = normalizeEntityName(parts[1].trim());
            String tgtId = normalizeEntityName(parts[2].trim());
            String keywords = parts[3].trim();
            String description = parts.length > 4 ? parts[4].trim() : "";
            
            if (srcId.isEmpty() || tgtId.isEmpty()) {
                return null;
            }
            
            // Prevent self-referential relationships (T008)
            if (srcId.equalsIgnoreCase(tgtId)) {
                logger.debug("Filtered self-referential relation: {} -> {}", srcId, tgtId);
                return null;
            }
            
            return Relation.builder()
                .srcId(srcId)
                .tgtId(tgtId)
                .description(description.isEmpty() ? "RELATED_TO" : description)
                .keywords(keywords)
                .weight(1.0)
                .build();
                
        } catch (Exception e) {
            logger.debug("Failed to parse relation line: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Merges two entity descriptions by concatenating them with a separator.
     * Implements simple description accumulation strategy (Option B from analysis).
     * 
     * @param existingDesc the existing accumulated description
     * @param newDesc the new description to merge in
     * @return merged description with separator, limited to max length
     */
    private String mergeDescriptions(@NotNull String existingDesc, @NotNull String newDesc) {
        // Skip if new description is identical to existing
        if (existingDesc.equals(newDesc)) {
            return existingDesc;
        }
        
        // Skip if new description is already contained in existing
        if (existingDesc.contains(newDesc)) {
            return existingDesc;
        }
        
        // Concatenate with separator from config
        String merged = existingDesc + config.entityDescriptionSeparator() + newDesc;
        
        // Truncate if exceeds max length from config
        if (merged.length() > config.entityDescriptionMaxLength()) {
            merged = merged.substring(0, config.entityDescriptionMaxLength() - 3) + "...";
        }
        
        return merged;
    }
    
    /**
     * Normalizes entity names for consistency.
     * 
     * <p>Normalization rules (per official LightRAG):</p>
     * <ol>
     *   <li>Remove surrounding quotes (single and double)</li>
     *   <li>Trim leading/trailing whitespace</li>
     *   <li>Collapse multiple internal spaces to single space</li>
     *   <li>Truncate to configured maximum length (default 500)</li>
     * </ol>
     * 
     * @param name the raw entity name from extraction
     * @param maxLength maximum allowed length (truncated if exceeded)
     * @return normalized entity name
     */
    private String normalizeEntityName(@NotNull String name, int maxLength) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        
        String normalized = name;
        
        // Remove surrounding quotes (both single and double)
        if ((normalized.startsWith("\"") && normalized.endsWith("\"")) ||
            (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        
        // Trim leading/trailing whitespace
        normalized = normalized.trim();
        
        // Collapse multiple internal spaces to single space
        normalized = normalized.replaceAll("\\s+", " ");
        
        // Truncate if exceeds max length
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
            logger.debug("Entity name truncated to {} chars: {}...", maxLength, normalized.substring(0, Math.min(50, normalized.length())));
        }
        
        return normalized;
    }
    
    /**
     * Default entity name normalization with configured max length.
     */
    private String normalizeEntityName(@NotNull String name) {
        // Default max length of 500 (official LightRAG default)
        return normalizeEntityName(name, 500);
    }
    
    /**
     * Stores entities and relations in graph storage and generates entity embeddings.
     * 
     * <p>Implementation Note - Entity Description Accumulation:</p>
     * <p>This method implements description merging for duplicate entities within the SAME batch
     * of entities being processed (from the same document chunks). Multiple descriptions for the
     * same entity are concatenated with a configurable separator.</p>
     * 
     * <p>Limitation - Apache AGE v1.5.0:</p>
     * <p>Due to a bug in Apache AGE v1.5.0, entity properties are NOT updated when the entity
     * already exists in the graph database (see AgeGraphStorage.java:71-73, 103-108).
     * This means:</p>
     * <ul>
     *   <li>Description merging works for entities within the SAME document/batch</li>
     *   <li>Description merging does NOT work across DIFFERENT documents processed at different times</li>
     *   <li>After upgrading to AGE v1.5.1+, proper upsert logic should be implemented</li>
     * </ul>
     * 
     * <p>For full LightRAG description summarization (using LLM to merge descriptions across
     * multiple documents), see the official LightRAG prompt.py:summarize_entity_descriptions.</p>
     */
    private CompletableFuture<Void> storeKnowledgeGraph(
        @NotNull List<Entity> entities,
        @NotNull List<Relation> relations,
        @Nullable Map<String, Object> metadata
    ) {
        if (entities.isEmpty() && relations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Extract projectId from metadata for graph isolation
        String graphProjectId = metadata != null ? (String) metadata.get("project_id") : null;
        if (graphProjectId == null) {
            throw new IllegalArgumentException("project_id is required in metadata for graph operations");
        }
        
        // Step 1: Apply semantic entity deduplication if enabled (T034, T035)
        List<Entity> entitiesToProcess = entities;
        if (entityResolver != null && deduplicationConfig != null && deduplicationConfig.enabled()) {
            logger.debug("Applying semantic entity deduplication for {} entities", entities.size());
            EntityResolutionResult resolutionResult = entityResolver.resolveDuplicatesWithStats(
                entities, 
                graphProjectId
            );
            entitiesToProcess = resolutionResult.resolvedEntities();
            
            // T036: Log resolution statistics
            if (resolutionResult.hadDuplicates()) {
                logger.info("Semantic deduplication: {} → {} entities (removed {} duplicates, {} clusters, {}ms)",
                    resolutionResult.originalEntityCount(),
                    resolutionResult.resolvedEntityCount(),
                    resolutionResult.duplicatesRemoved(),
                    resolutionResult.clustersFound(),
                    resolutionResult.processingTime().toMillis()
                );
            }
        }
        
        // Step 2: Deduplicate entities by exact name match and accumulate descriptions
        Map<String, Entity> uniqueEntities = new HashMap<>();
        for (Entity entity : entitiesToProcess) {
            String entityName = entity.getEntityName();
            if (uniqueEntities.containsKey(entityName)) {
                // Entity already exists in this batch - merge descriptions
                Entity existing = uniqueEntities.get(entityName);
                String mergedDescription = mergeDescriptions(
                    existing.getDescription(), 
                    entity.getDescription()
                );
                uniqueEntities.put(entityName, existing.withDescription(mergedDescription));
            } else {
                // First occurrence of this entity in this batch
                uniqueEntities.put(entityName, entity);
            }
        }
        
        // Store entities in graph using batch operation (reduces connection pool usage)
        CompletableFuture<Void> entitiesFuture = graphStorage.upsertEntities(graphProjectId, new ArrayList<>(uniqueEntities.values()));
        
        // Store relations in graph using batch operation (reduces connection pool usage)
        // IMPORTANT: Relations must wait for entities to complete first to avoid race conditions
        // where relation MERGE creates name-only entities before entity upsert completes
        CompletableFuture<Void> relationsFuture = entitiesFuture
            .thenCompose(v -> graphStorage.upsertRelations(graphProjectId, relations));
        
        // Generate and store entity embeddings
        List<String> entityTexts = uniqueEntities.values().stream()
            .map(e -> e.getEntityName() + ": " + e.getDescription())
            .toList();
        
        CompletableFuture<Void> embeddingsFuture = CompletableFuture.completedFuture(null);
        
        if (!entityTexts.isEmpty()) {
            embeddingsFuture = embeddingFunction.embed(entityTexts)
                .thenCompose(embeddings -> {
                    List<VectorStorage.VectorEntry> vectorEntries = new ArrayList<>();
                    int i = 0;
                    for (Entity entity : uniqueEntities.values()) {
                        if (i < embeddings.size()) {
                            String projectId = metadata != null ? (String) metadata.get("project_id") : null;
                            String documentId = metadata != null ? (String) metadata.get("document_id") : null;
                            VectorStorage.VectorMetadata vectorMetadata = new VectorStorage.VectorMetadata(
                                "entity",
                                entity.getEntityName(),
                                documentId,  // documentId from metadata (links entity to source document)
                                null,  // chunkIndex (entities are aggregated across chunks)
                                projectId  // projectId (UUID from the project table)
                            );
                            
                            // Generate deterministic UUID for entity vector (same entity = same ID)
                            // This ensures cross-batch deduplication: if entity appears in multiple batches,
                            // PgVector's ON CONFLICT will update the existing vector instead of creating duplicates
                            String deterministicId = generateEntityVectorId(entity.getEntityName(), projectId);
                            
                            vectorEntries.add(new VectorStorage.VectorEntry(
                                deterministicId,
                                embeddings.get(i),
                                vectorMetadata
                            ));
                            i++;
                        }
                    }
                    return entityVectorStorage.upsertBatch(vectorEntries);
                });
        }
        
        // Wait for all storage operations to complete
        return CompletableFuture.allOf(
            entitiesFuture,
            relationsFuture,
            embeddingsFuture
        );
    }
    
    // Query execution methods - delegate to appropriate executors
    private CompletableFuture<LightRAGQueryResult> executeLocalQuery(String query, QueryParam param) {
        return localExecutor.execute(query, param);
    }
    
    private CompletableFuture<LightRAGQueryResult> executeGlobalQuery(String query, QueryParam param) {
        return globalExecutor.execute(query, param);
    }
    
    private CompletableFuture<LightRAGQueryResult> executeHybridQuery(String query, QueryParam param) {
        return hybridExecutor.execute(query, param);
    }
    
    private CompletableFuture<LightRAGQueryResult> executeNaiveQuery(String query, QueryParam param) {
        return naiveExecutor.execute(query, param);
    }
    
    private CompletableFuture<LightRAGQueryResult> executeMixQuery(String query, QueryParam param) {
        return mixExecutor.execute(query, param);
    }
    
    private CompletableFuture<LightRAGQueryResult> executeBypassQuery(String query, QueryParam param) {
        logger.debug("Executing BYPASS query");
        // BYPASS mode just calls LLM directly without RAG - no sources to return
        return llmFunction.apply(query)
            .thenApply(answer -> new LightRAGQueryResult(
                answer,
                List.of(),  // No source chunks in BYPASS mode
                param.getMode(),
                0
            ));
    }
    
    /**
     * Generates a deterministic UUID for entity vectors based on entity name and project ID.
     * This ensures that the same entity across different batches gets the same vector ID,
     * enabling PgVector's ON CONFLICT DO UPDATE to deduplicate instead of creating duplicates.
     * 
     * @param entityName The entity name
     * @param projectId The project ID (nullable)
     * @return Deterministic UUID string
     */
    private String generateEntityVectorId(@NotNull String entityName, @Nullable String projectId) {
        // Create composite key: projectId:entityName
        String composite = (projectId != null ? projectId : "global") + ":" + entityName;
        return UuidUtils.deterministicV5(composite).toString();
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                "LightRAG not initialized. Call initialize() before using."
            );
        }
    }
    
    /**
     * Configuration for LightRAG.
     */
    public record LightRAGConfig(
        int chunkSize,
        int chunkOverlap,
        int maxTokens,
        int topK,
        boolean enableCache,
        int kgExtractionBatchSize,
        int embeddingBatchSize,
        int entityDescriptionMaxLength,
        String entityDescriptionSeparator
    ) {
        public static LightRAGConfig defaults() {
            return new LightRAGConfig(
                1200,  // chunkSize
                100,   // chunkOverlap
                4000,  // maxTokens
                10,    // topK
                true,  // enableCache
                20,    // kgExtractionBatchSize
                32,    // embeddingBatchSize
                1000,  // entityDescriptionMaxLength
                " | "  // entityDescriptionSeparator
            );
        }
    }
}
