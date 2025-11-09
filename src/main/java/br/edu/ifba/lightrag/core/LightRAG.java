package br.edu.ifba.lightrag.core;

import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.query.*;
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
    
    // System prompts for query modes
    private final String localSystemPrompt;
    private final String globalSystemPrompt;
    private final String hybridSystemPrompt;
    private final String naiveSystemPrompt;
    private final String mixSystemPrompt;
    private final String bypassSystemPrompt;
    
    // System prompt for entity extraction
    private final String entityExtractionSystemPrompt;
    
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
                entityExtractionSystemPrompt
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
        @NotNull String entityExtractionSystemPrompt
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
        this.localSystemPrompt = localSystemPrompt;
        this.globalSystemPrompt = globalSystemPrompt;
        this.hybridSystemPrompt = hybridSystemPrompt;
        this.naiveSystemPrompt = naiveSystemPrompt;
        this.mixSystemPrompt = mixSystemPrompt;
        this.bypassSystemPrompt = bypassSystemPrompt;
        this.entityExtractionSystemPrompt = entityExtractionSystemPrompt;
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
                mixSystemPrompt
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
        
        logger.debug("Document {} chunked into {} pieces", docId, chunks.size());
        
        // Step 2: Store chunks and generate embeddings
        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
        List<VectorStorage.VectorEntry> vectorEntries = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = UuidUtils.randomV7().toString();
            String chunkContent = chunks.get(i);
            final int chunkIndex = i;  // Capture index for lambda
            
            // Store chunk content
            CompletableFuture<Void> chunkFuture = chunkStorage.set(chunkId, chunkContent)
                .thenCompose(v -> embeddingFunction.embedSingle(chunkContent))
                .thenAccept(embedding -> {
                    // Collect vector entries for batch upsert
                    String documentId = metadata != null ? (String) metadata.get("document_id") : null;
                    String projectId = metadata != null ? (String) metadata.get("project_id") : null;
                    VectorStorage.VectorMetadata vectorMetadata = new VectorStorage.VectorMetadata(
                        "chunk",
                        chunkContent,
                        docId,  // sourceId (the document ID from LightRAG perspective)
                        documentId,  // documentId (UUID from the document table)
                        chunkIndex,
                        projectId  // projectId (UUID from the project table)
                    );
                    synchronized (vectorEntries) {
                        vectorEntries.add(new VectorStorage.VectorEntry(chunkId, embedding, vectorMetadata));
                    }
                });
            
            chunkFutures.add(chunkFuture);
        }
        
        // Step 3: Wait for all chunks to be processed, then batch upsert vectors
        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> chunkVectorStorage.upsertBatch(vectorEntries))
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
     * 2. Calls LLM to extract entities and relations
     * 3. Parses JSON responses into Entity/Relation objects
     * 4. Upserts entities and relations to graph storage
     * 5. Generates and stores entity embeddings in vector storage
     */
    private CompletableFuture<KGExtractionResult> extractKnowledgeGraph(
        @NotNull String docId,
        @NotNull List<String> chunks,
        @Nullable Map<String, Object> metadata
    ) {
        logger.debug("Extracting knowledge graph from {} chunks", chunks.size());
        
        if (chunks.isEmpty()) {
            return CompletableFuture.completedFuture(new KGExtractionResult(0, 0));
        }
        
        // Process chunks in parallel and extract entities/relations
        List<CompletableFuture<KGExtractionChunkResult>> extractionFutures = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = UuidUtils.randomV7().toString();
            String chunkContent = chunks.get(i);
            extractionFutures.add(extractKnowledgeGraphFromChunk(chunkId, chunkContent));
        }
        
        // Wait for all extractions to complete
        return CompletableFuture.allOf(extractionFutures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                // Collect all entities and relations
                List<Entity> allEntities = new ArrayList<>();
                List<Relation> allRelations = new ArrayList<>();
                
                for (CompletableFuture<KGExtractionChunkResult> future : extractionFutures) {
                    KGExtractionChunkResult result = future.join();
                    allEntities.addAll(result.entities);
                    allRelations.addAll(result.relations);
                }
                
                logger.debug("Extracted {} entities and {} relations from document {}", 
                    allEntities.size(), allRelations.size(), docId);
                
                // Store entities and relations in graph storage
                return storeKnowledgeGraph(allEntities, allRelations, metadata)
                    .thenApply(ignored -> new KGExtractionResult(allEntities.size(), allRelations.size()));
            });
    }
    
    /**
     * Result of KG extraction from a single chunk.
     */
    private record KGExtractionChunkResult(List<Entity> entities, List<Relation> relations) {}
    
    /**
     * Extracts entities and relations from a single chunk using LLM.
     */
    private CompletableFuture<KGExtractionChunkResult> extractKnowledgeGraphFromChunk(
        @NotNull String chunkId,
        @NotNull String chunkContent
    ) {
        // Build extraction prompt
        String prompt = buildKGExtractionPrompt(chunkContent);
        
        // Call LLM to extract entities and relations
        return llmFunction.apply(prompt, entityExtractionSystemPrompt)
            .thenApply(response -> parseKGExtractionResponse(chunkId, response))
            .exceptionally(e -> {
                logger.warn("Failed to extract KG from chunk {}: {}", chunkId, e.getMessage());
                return new KGExtractionChunkResult(List.of(), List.of());
            });
    }
    
    /**
     * Builds a prompt for entity/relation extraction.
     */
    private String buildKGExtractionPrompt(@NotNull String text) {
        return String.format(
            """
            Extract all entities and relationships from the following text.
            Focus on identifying key concepts, people, organizations, locations, and their relationships.
            
            Text:
            %s
            
            Return the extracted entities and relationships in the JSON format specified in the system prompt.
            """,
            text
        );
    }
    
    /**
     * Parses LLM response into entities and relations.
     * Expects JSON format: {"entities": [...], "relationships": [...]}
     */
    private KGExtractionChunkResult parseKGExtractionResponse(
        @NotNull String chunkId,
        @NotNull String response
    ) {
        try {
            // Simple JSON parsing (in production, use Jackson or similar)
            List<Entity> entities = new ArrayList<>();
            List<Relation> relations = new ArrayList<>();
            
            // Find entities array
            int entitiesStart = response.indexOf("\"entities\"");
            if (entitiesStart >= 0) {
                int arrayStart = response.indexOf("[", entitiesStart);
                int arrayEnd = findMatchingBracket(response, arrayStart, '[', ']');
                
                if (arrayStart >= 0 && arrayEnd > arrayStart) {
                    String entitiesJson = response.substring(arrayStart + 1, arrayEnd);
                    entities = parseEntities(entitiesJson, chunkId);
                }
            }
            
            // Find relationships array
            int relsStart = response.indexOf("\"relationships\"");
            if (relsStart < 0) {
                relsStart = response.indexOf("\"relations\"");
            }
            
            if (relsStart >= 0) {
                int arrayStart = response.indexOf("[", relsStart);
                int arrayEnd = findMatchingBracket(response, arrayStart, '[', ']');
                
                if (arrayStart >= 0 && arrayEnd > arrayStart) {
                    String relationsJson = response.substring(arrayStart + 1, arrayEnd);
                    relations = parseRelations(relationsJson, chunkId, entities);
                }
            }
            
            return new KGExtractionChunkResult(entities, relations);
            
        } catch (Exception e) {
            logger.warn("Failed to parse KG extraction response: {}", e.getMessage());
            return new KGExtractionChunkResult(List.of(), List.of());
        }
    }
    
    /**
     * Finds the matching closing bracket for an opening bracket.
     */
    private int findMatchingBracket(String text, int start, char open, char close) {
        if (start < 0 || start >= text.length() || text.charAt(start) != open) {
            return -1;
        }
        
        int depth = 1;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
    
    /**
     * Parses entities from JSON array string.
     */
    private List<Entity> parseEntities(@NotNull String entitiesJson, @NotNull String sourceChunkId) {
        List<Entity> entities = new ArrayList<>();
        
        // Split by object boundaries
        String[] entityObjects = entitiesJson.split("\\},\\s*\\{");
        
        for (String entityObj : entityObjects) {
            try {
                String cleaned = entityObj.replace("{", "").replace("}", "").trim();
                if (cleaned.isEmpty()) continue;
                
                String entityName = extractJsonField(cleaned, "entity_name");
                String entityType = extractJsonField(cleaned, "entity_type");
                String description = extractJsonField(cleaned, "description");
                
                if (entityName != null && !entityName.isEmpty()) {
                    Entity entity = Entity.builder()
                        .entityName(entityName)
                        .entityType(entityType != null ? entityType : "CONCEPT")
                        .description(description != null ? description : "")
                        .sourceId(sourceChunkId)
                        .build();
                    
                    entities.add(entity);
                }
            } catch (Exception e) {
                logger.debug("Failed to parse entity object: {}", e.getMessage());
            }
        }
        
        return entities;
    }
    
    /**
     * Parses relations from JSON array string.
     */
    private List<Relation> parseRelations(
        @NotNull String relationsJson,
        @NotNull String sourceChunkId,
        @NotNull List<Entity> entities
    ) {
        List<Relation> relations = new ArrayList<>();
        
        // Build entity name set for validation
        Map<String, String> entityNameMap = new HashMap<>();
        for (Entity entity : entities) {
            entityNameMap.put(entity.getEntityName().toLowerCase(), entity.getEntityName());
        }
        
        // Split by object boundaries
        String[] relationObjects = relationsJson.split("\\},\\s*\\{");
        
        for (String relationObj : relationObjects) {
            try {
                String cleaned = relationObj.replace("{", "").replace("}", "").trim();
                if (cleaned.isEmpty()) continue;
                
                String srcId = extractJsonField(cleaned, "src_id");
                String tgtId = extractJsonField(cleaned, "tgt_id");
                String description = extractJsonField(cleaned, "description");
                String keywords = extractJsonField(cleaned, "keywords");
                
                // Try to resolve entity names (srcId and tgtId are entity names in the extraction)
                if (srcId != null && tgtId != null) {
                    // Use the actual entity names from entities, or the extracted names if not found
                    String srcEntityName = entityNameMap.getOrDefault(srcId.toLowerCase(), srcId);
                    String tgtEntityName = entityNameMap.getOrDefault(tgtId.toLowerCase(), tgtId);
                    
                    Relation relation = Relation.builder()
                        .srcId(srcEntityName)
                        .tgtId(tgtEntityName)
                        .description(description != null ? description : "RELATED_TO")
                        .keywords(keywords != null ? keywords : "")
                        .sourceId(sourceChunkId)
                        .weight(1.0)
                        .build();
                    
                    relations.add(relation);
                }
            } catch (Exception e) {
                logger.debug("Failed to parse relation object: {}", e.getMessage());
            }
        }
        
        return relations;
    }
    
    /**
     * Extracts a field value from a simple JSON-like string.
     */
    private String extractJsonField(@NotNull String json, @NotNull String fieldName) {
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    /**
     * Stores entities and relations in graph storage and generates entity embeddings.
     */
    private CompletableFuture<Void> storeKnowledgeGraph(
        @NotNull List<Entity> entities,
        @NotNull List<Relation> relations,
        @Nullable Map<String, Object> metadata
    ) {
        if (entities.isEmpty() && relations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Deduplicate entities by name
        Map<String, Entity> uniqueEntities = new HashMap<>();
        for (Entity entity : entities) {
            uniqueEntities.putIfAbsent(entity.getEntityName(), entity);
        }
        
        // Store entities in graph using batch operation (reduces connection pool usage)
        CompletableFuture<Void> entitiesFuture = graphStorage.upsertEntities(new ArrayList<>(uniqueEntities.values()));
        
        // Store relations in graph using batch operation (reduces connection pool usage)
        CompletableFuture<Void> relationsFuture = graphStorage.upsertRelations(relations);
        
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
                            VectorStorage.VectorMetadata vectorMetadata = new VectorStorage.VectorMetadata(
                                "entity",
                                entity.getEntityName(),
                                entity.getSourceId(),  // sourceId from entity
                                null,  // documentId (entities are not directly tied to documents)
                                null,  // chunkIndex (entities are not tied to specific chunks)
                                projectId  // projectId (UUID from the project table)
                            );
                            vectorEntries.add(new VectorStorage.VectorEntry(
                                UuidUtils.randomV7().toString(),  // Generate UUID for vector ID
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
        boolean enableCache
    ) {
        public static LightRAGConfig defaults() {
            return new LightRAGConfig(
                1200,  // chunkSize
                100,   // chunkOverlap
                4000,  // maxTokens
                10,    // topK
                true   // enableCache
            );
        }
    }
}
