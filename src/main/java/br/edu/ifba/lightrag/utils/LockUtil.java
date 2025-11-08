package br.edu.ifba.lightrag.utils;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Utility class for managing locks in concurrent scenarios.
 * Provides normalized key generation for relation pairs to prevent deadlocks.
 * This is critical for the knowledge graph processing pipeline.
 */
public final class LockUtil {
    
    private static final ConcurrentHashMap<String, ReentrantLock> LOCK_POOL = new ConcurrentHashMap<>();
    
    private LockUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Gets a reentrant lock for a given key.
     * Uses a pool to ensure same key always returns same lock instance.
     *
     * @param key The lock key
     * @return ReentrantLock for the key
     */
    @NotNull
    public static ReentrantLock getLock(@NotNull String key) {
        return LOCK_POOL.computeIfAbsent(key, k -> new ReentrantLock(true)); // fair lock
    }
    
    /**
     * Generates a normalized key for a relation pair.
     * Always returns keys in sorted order to prevent deadlocks.
     * This matches the Python implementation's getNormalizedPair() method.
     *
     * Example: ("A", "B") and ("B", "A") both return "A::B"
     *
     * @param srcId Source entity ID
     * @param tgtId Target entity ID
     * @return Normalized key string
     */
    @NotNull
    public static String normalizeRelationPair(@NotNull String srcId, @NotNull String tgtId) {
        String[] pair = {srcId, tgtId};
        Arrays.sort(pair);
        return pair[0] + "::" + pair[1];
    }
    
    /**
     * Acquires locks for multiple keys in sorted order to prevent deadlocks.
     *
     * @param keys Keys to lock (will be sorted internally)
     * @return Array of acquired locks in sorted key order
     */
    @NotNull
    public static ReentrantLock[] acquireLocksInOrder(@NotNull String... keys) {
        String[] sortedKeys = Arrays.copyOf(keys, keys.length);
        Arrays.sort(sortedKeys);
        
        ReentrantLock[] locks = new ReentrantLock[sortedKeys.length];
        for (int i = 0; i < sortedKeys.length; i++) {
            locks[i] = getLock(sortedKeys[i]);
            locks[i].lock();
        }
        
        return locks;
    }
    
    /**
     * Releases an array of locks.
     *
     * @param locks Locks to release
     */
    public static void releaseLocks(@NotNull ReentrantLock... locks) {
        for (ReentrantLock lock : locks) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * Clears the lock pool (useful for testing).
     */
    public static void clearLockPool() {
        LOCK_POOL.clear();
    }
}
