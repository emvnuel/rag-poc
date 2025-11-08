package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.DocStatusStorage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of document status storage.
 * Stores document statuses in a ConcurrentHashMap for thread-safe access.
 * Data is not persisted - only exists in memory during runtime.
 */
public class InMemoryDocStatusStorage implements DocStatusStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(InMemoryDocStatusStorage.class);
    
    private final ConcurrentHashMap<String, DocumentStatus> storage;
    private volatile boolean initialized = false;
    
    public InMemoryDocStatusStorage() {
        this.storage = new ConcurrentHashMap<>();
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!initialized) {
                initialized = true;
                logger.info("InMemoryDocStatusStorage initialized");
            }
        });
    }
    
    @Override
    public CompletableFuture<DocumentStatus> getStatus(@NotNull String docId) {
        ensureInitialized();
        return CompletableFuture.completedFuture(storage.get(docId));
    }
    
    @Override
    public CompletableFuture<List<DocumentStatus>> getStatuses(@NotNull List<String> docIds) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            List<DocumentStatus> result = new ArrayList<>();
            for (String docId : docIds) {
                DocumentStatus status = storage.get(docId);
                if (status != null) {
                    result.add(status);
                }
            }
            return result;
        });
    }
    
    @Override
    public CompletableFuture<Void> setStatus(@NotNull DocumentStatus status) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.put(status.docId(), status);
            logger.debug("Set status for document: {} - {}", status.docId(), status.processingStatus());
        });
    }
    
    @Override
    public CompletableFuture<Void> setStatuses(@NotNull List<DocumentStatus> statuses) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            for (DocumentStatus status : statuses) {
                storage.put(status.docId(), status);
            }
            logger.debug("Set status for {} documents", statuses.size());
        });
    }
    
    @Override
    public CompletableFuture<Boolean> deleteStatus(@NotNull String docId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            boolean existed = storage.remove(docId) != null;
            if (existed) {
                logger.debug("Deleted status for document: {}", docId);
            }
            return existed;
        });
    }
    
    @Override
    public CompletableFuture<Integer> deleteStatuses(@NotNull List<String> docIds) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (String docId : docIds) {
                if (storage.remove(docId) != null) {
                    count++;
                }
            }
            logger.debug("Deleted status for {} documents", count);
            return count;
        });
    }
    
    @Override
    public CompletableFuture<List<DocumentStatus>> getAllStatuses() {
        ensureInitialized();
        return CompletableFuture.completedFuture(new ArrayList<>(storage.values()));
    }
    
    @Override
    public CompletableFuture<List<DocumentStatus>> getStatusesByProcessingStatus(@NotNull ProcessingStatus processingStatus) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() ->
            storage.values().stream()
                .filter(status -> status.processingStatus() == processingStatus)
                .toList()
        );
    }
    
    @Override
    public CompletableFuture<Void> clear() {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.clear();
            logger.info("Cleared all document statuses");
        });
    }
    
    @Override
    public CompletableFuture<Long> size() {
        ensureInitialized();
        return CompletableFuture.completedFuture((long) storage.size());
    }
    
    @Override
    public void close() throws Exception {
        if (initialized) {
            storage.clear();
            initialized = false;
            logger.info("InMemoryDocStatusStorage closed");
        }
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Storage not initialized. Call initialize() first.");
        }
    }
}
