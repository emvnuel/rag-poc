package br.edu.ifba.lightrag.storage;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for key-value storage operations.
 * Used for storing LLM response cache, text chunks, and document information.
 * 
 * Implementations: JsonKVStorage, PGKVStorage, RedisKVStorage, MongoKVStorage
 */
public interface KVStorage extends AutoCloseable {
    
    /**
     * Initializes the storage backend.
     * Must be called before any other operations.
     */
    CompletableFuture<Void> initialize();
    
    /**
     * Gets a value by key.
     *
     * @param key the key to retrieve
     * @return the value associated with the key, or null if not found
     */
    CompletableFuture<String> get(@NotNull String key);
    
    /**
     * Gets multiple values by keys.
     *
     * @param keys the keys to retrieve
     * @return a map of keys to values (missing keys will not be in the map)
     */
    CompletableFuture<Map<String, String>> getBatch(@NotNull List<String> keys);
    
    /**
     * Sets a value for a key.
     *
     * @param key the key to set
     * @param value the value to store
     */
    CompletableFuture<Void> set(@NotNull String key, @NotNull String value);
    
    /**
     * Sets multiple key-value pairs.
     *
     * @param entries the key-value pairs to store
     */
    CompletableFuture<Void> setBatch(@NotNull Map<String, String> entries);
    
    /**
     * Deletes a value by key.
     *
     * @param key the key to delete
     * @return true if the key was deleted, false if it didn't exist
     */
    CompletableFuture<Boolean> delete(@NotNull String key);
    
    /**
     * Deletes multiple values by keys.
     *
     * @param keys the keys to delete
     * @return the number of keys that were deleted
     */
    CompletableFuture<Integer> deleteBatch(@NotNull List<String> keys);
    
    /**
     * Checks if a key exists.
     *
     * @param key the key to check
     * @return true if the key exists, false otherwise
     */
    CompletableFuture<Boolean> exists(@NotNull String key);
    
    /**
     * Gets all keys in the storage.
     *
     * @return a list of all keys
     */
    CompletableFuture<List<String>> keys();
    
    /**
     * Gets all keys matching a pattern.
     *
     * @param pattern the pattern to match (implementation-specific)
     * @return a list of matching keys
     */
    CompletableFuture<List<String>> keys(@NotNull String pattern);
    
    /**
     * Clears all data in the storage.
     */
    CompletableFuture<Void> clear();
    
    /**
     * Gets the number of entries in the storage.
     *
     * @return the number of entries
     */
    CompletableFuture<Long> size();
    
    /**
     * Closes the storage and releases resources.
     */
    @Override
    void close() throws Exception;
}
