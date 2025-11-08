package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.KVStorage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * File-based JSON key-value storage implementation.
 * Stores data in a JSON file with concurrent access support.
 */
public class JsonKVStorage implements KVStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonKVStorage.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Path filePath;
    private final ConcurrentHashMap<String, String> storage;
    private final ReadWriteLock lock;
    private volatile boolean initialized = false;
    
    /**
     * Creates a new JsonKVStorage instance.
     *
     * @param filePath Path to the JSON storage file
     */
    public JsonKVStorage(@NotNull String filePath) {
        this.filePath = Paths.get(filePath);
        this.storage = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (initialized) {
                return;
            }
            
            lock.writeLock().lock();
            try {
                // Create parent directories if needed
                Path parent = filePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                
                // Load existing data if file exists
                if (Files.exists(filePath)) {
                    logger.info("Loading existing data from: {}", filePath);
                    Map<String, String> data = mapper.readValue(
                        filePath.toFile(),
                        new TypeReference<Map<String, String>>() {}
                    );
                    storage.putAll(data);
                    logger.info("Loaded {} entries from storage", storage.size());
                } else {
                    logger.info("Creating new storage file: {}", filePath);
                    save();
                }
                
                initialized = true;
            } catch (IOException e) {
                logger.error("Failed to initialize storage", e);
                throw new RuntimeException("Storage initialization failed", e);
            } finally {
                lock.writeLock().unlock();
            }
        });
    }
    
    @Override
    public CompletableFuture<String> get(@NotNull String key) {
        ensureInitialized();
        return CompletableFuture.completedFuture(storage.get(key));
    }
    
    @Override
    public CompletableFuture<Map<String, String>> getBatch(@NotNull List<String> keys) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> result = new HashMap<>();
            for (String key : keys) {
                String value = storage.get(key);
                if (value != null) {
                    result.put(key, value);
                }
            }
            return result;
        });
    }
    
    @Override
    public CompletableFuture<Void> set(@NotNull String key, @NotNull String value) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.put(key, value);
            save();
        });
    }
    
    @Override
    public CompletableFuture<Void> setBatch(@NotNull Map<String, String> entries) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.putAll(entries);
            save();
        });
    }
    
    @Override
    public CompletableFuture<Boolean> delete(@NotNull String key) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            boolean existed = storage.remove(key) != null;
            if (existed) {
                save();
            }
            return existed;
        });
    }
    
    @Override
    public CompletableFuture<Integer> deleteBatch(@NotNull List<String> keys) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (String key : keys) {
                if (storage.remove(key) != null) {
                    count++;
                }
            }
            if (count > 0) {
                save();
            }
            return count;
        });
    }
    
    @Override
    public CompletableFuture<Boolean> exists(@NotNull String key) {
        ensureInitialized();
        return CompletableFuture.completedFuture(storage.containsKey(key));
    }
    
    @Override
    public CompletableFuture<List<String>> keys() {
        ensureInitialized();
        return CompletableFuture.completedFuture(new ArrayList<>(storage.keySet()));
    }
    
    @Override
    public CompletableFuture<List<String>> keys(@NotNull String pattern) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            // Convert simple glob pattern to regex
            String regexPattern = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            Pattern p = Pattern.compile(regexPattern);
            
            return storage.keySet().stream()
                .filter(key -> p.matcher(key).matches())
                .toList();
        });
    }
    
    @Override
    public CompletableFuture<Void> clear() {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            storage.clear();
            save();
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
            lock.writeLock().lock();
            try {
                save();
                storage.clear();
                initialized = false;
                logger.info("Storage closed");
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
    
    /**
     * Persists the current storage state to disk.
     */
    private void save() {
        lock.readLock().lock();
        try {
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(filePath.toFile(), storage);
        } catch (IOException e) {
            logger.error("Failed to save storage to disk", e);
            throw new RuntimeException("Storage save failed", e);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Storage not initialized. Call initialize() first.");
        }
    }
}
