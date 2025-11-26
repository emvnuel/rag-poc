package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.utils.EmbeddingUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory vector storage implementation.
 * Uses brute-force cosine similarity search for vector queries.
 * Suitable for development and small-scale deployments.
 */
public class InMemoryVectorStorage implements VectorStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(InMemoryVectorStorage.class);
    
    private final ConcurrentHashMap<String, VectorEntry> storage;
    private volatile boolean initialized = false;
    
    public InMemoryVectorStorage() {
        this.storage = new ConcurrentHashMap<>();
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!initialized) {
                initialized = true;
                logger.info("InMemoryVectorStorage initialized");
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> upsert(
        @NotNull String id,
        @NotNull Object vector,
        @NotNull VectorMetadata metadata
    ) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.put(id, new VectorEntry(id, vector, metadata));
            logger.debug("Upserted vector: {}", id);
        });
    }
    
    @Override
    public CompletableFuture<Void> upsertBatch(@NotNull List<VectorEntry> entries) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            for (VectorEntry entry : entries) {
                storage.put(entry.id(), entry);
            }
            logger.debug("Upserted {} vectors", entries.size());
        });
    }
    
    @Override
    public CompletableFuture<List<VectorSearchResult>> query(
        @NotNull Object queryVector,
        int topK,
        VectorFilter filter
    ) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            // Convert query vector to float array
            float[] queryArray = vectorToFloatArray(queryVector);
            
            // Calculate similarities for all vectors
            List<ScoredVector> scored = new ArrayList<>();
            
            for (VectorEntry entry : storage.values()) {
                // Apply filter if provided
                if (filter != null) {
                    if (filter.type() != null && !filter.type().equals(entry.metadata().type())) {
                        continue;
                    }
                    if (filter.ids() != null && !filter.ids().contains(entry.id())) {
                        continue;
                    }
                }
                
                float[] entryArray = vectorToFloatArray(entry.vector());
                double similarity = EmbeddingUtil.cosineSimilarity(queryArray, entryArray);
                scored.add(new ScoredVector(entry.id(), similarity, entry.metadata()));
            }
            
            // Sort by similarity (descending) and take top K
            return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredVector::score).reversed())
                .limit(topK)
                .map(sv -> new VectorSearchResult(sv.id, sv.score, sv.metadata))
                .toList();
        });
    }
    
    @Override
    public CompletableFuture<Boolean> delete(@NotNull String id) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            boolean existed = storage.remove(id) != null;
            if (existed) {
                logger.debug("Deleted vector: {}", id);
            }
            return existed;
        });
    }
    
    @Override
    public CompletableFuture<Integer> deleteBatch(@NotNull List<String> ids) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (String id : ids) {
                if (storage.remove(id) != null) {
                    count++;
                }
            }
            logger.debug("Deleted {} vectors", count);
            return count;
        });
    }
    
    @Override
    public CompletableFuture<VectorEntry> get(@NotNull String id) {
        ensureInitialized();
        return CompletableFuture.completedFuture(storage.get(id));
    }
    
    @Override
    public CompletableFuture<Void> clear() {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.clear();
            logger.info("Cleared all vectors");
        });
    }
    
    @Override
    public CompletableFuture<Long> size() {
        ensureInitialized();
        return CompletableFuture.completedFuture((long) storage.size());
    }
    
    // ===== Batch Delete Operations (spec-007) =====
    
    @Override
    public CompletableFuture<Integer> deleteEntityEmbeddings(@NotNull String projectId, @NotNull Set<String> entityNames) {
        ensureInitialized();
        if (entityNames == null || entityNames.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            int deletedCount = 0;
            
            // Find and delete vectors where type='entity' and content matches any entity name
            List<String> toDelete = new ArrayList<>();
            for (VectorEntry entry : storage.values()) {
                if ("entity".equals(entry.metadata().type()) && entityNames.contains(entry.metadata().content())) {
                    toDelete.add(entry.id());
                }
            }
            
            for (String id : toDelete) {
                if (storage.remove(id) != null) {
                    deletedCount++;
                }
            }
            
            logger.debug("Deleted {} entity embeddings for project {}", deletedCount, projectId);
            return deletedCount;
        });
    }
    
    @Override
    public CompletableFuture<Integer> deleteChunkEmbeddings(@NotNull String projectId, @NotNull Set<String> chunkIds) {
        ensureInitialized();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            int deletedCount = 0;
            
            // Find and delete vectors where type='chunk' and id matches any chunk ID
            for (String chunkId : chunkIds) {
                if (storage.remove(chunkId) != null) {
                    deletedCount++;
                }
            }
            
            logger.debug("Deleted {} chunk embeddings for project {}", deletedCount, projectId);
            return deletedCount;
        });
    }
    
    @Override
    public CompletableFuture<List<String>> getChunkIdsByDocumentId(@NotNull String projectId, @NotNull String documentId) {
        ensureInitialized();
        
        return CompletableFuture.supplyAsync(() -> {
            List<String> chunkIds = new ArrayList<>();
            
            // Find all chunks belonging to this document
            for (VectorEntry entry : storage.values()) {
                if ("chunk".equals(entry.metadata().type()) 
                    && documentId.equals(entry.metadata().documentId())
                    && (projectId.equals(entry.metadata().projectId()) || entry.metadata().projectId() == null)) {
                    chunkIds.add(entry.id());
                }
            }
            
            logger.debug("Found {} chunks for document {} in project {}", chunkIds.size(), documentId, projectId);
            return chunkIds;
        });
    }
    
    @Override
    public void close() throws Exception {
        if (initialized) {
            storage.clear();
            initialized = false;
            logger.info("InMemoryVectorStorage closed");
        }
    }
    
    /**
     * Converts a vector object to a float array.
     * Handles both base64 encoded strings and native float arrays.
     */
    private float[] vectorToFloatArray(@NotNull Object vector) {
        if (vector instanceof float[] floatArray) {
            return floatArray;
        } else if (vector instanceof double[] doubleArray) {
            float[] result = new float[doubleArray.length];
            for (int i = 0; i < doubleArray.length; i++) {
                result[i] = (float) doubleArray[i];
            }
            return result;
        } else if (vector instanceof String base64) {
            return EmbeddingUtil.fromBase64(base64);
        } else if (vector instanceof List<?> list) {
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                if (elem instanceof Number number) {
                    result[i] = number.floatValue();
                } else {
                    throw new IllegalArgumentException("List contains non-numeric element: " + elem);
                }
            }
            return result;
        } else {
            throw new IllegalArgumentException("Unsupported vector type: " + vector.getClass());
        }
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Storage not initialized. Call initialize() first.");
        }
    }
    
    /**
     * Internal class for tracking scored vectors during search.
     */
    private record ScoredVector(String id, double score, VectorMetadata metadata) {}
}
