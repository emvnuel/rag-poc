package br.edu.ifba.lightrag.storage;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for vector storage operations.
 * Used for storing and retrieving embedding vectors for entities, relations, and chunks.
 * 
 * Implementations: NanoVectorDBStorage, PGVectorStorage, MilvusVectorDBStorage,
 *                  ChromaVectorDBStorage, FaissVectorDBStorage, MongoVectorDBStorage,
 *                  QdrantVectorDBStorage
 */
public interface VectorStorage extends AutoCloseable {
    
    /**
     * Initializes the vector storage backend.
     * Must be called before any other operations.
     */
    CompletableFuture<Void> initialize();
    
    /**
     * Upserts (inserts or updates) a vector with metadata.
     *
     * @param id the unique identifier for the vector
     * @param vector the embedding vector (can be base64 encoded or raw array)
     * @param metadata additional metadata associated with the vector
     */
    CompletableFuture<Void> upsert(@NotNull String id, @NotNull Object vector, @NotNull VectorMetadata metadata);
    
    /**
     * Upserts multiple vectors with metadata.
     *
     * @param entries the list of vector entries to upsert
     */
    CompletableFuture<Void> upsertBatch(@NotNull List<VectorEntry> entries);
    
    /**
     * Queries for similar vectors using cosine similarity or other metrics.
     *
     * @param queryVector the query vector
     * @param topK the number of top results to return
     * @param filter optional metadata filter
     * @return a list of search results with IDs, scores, and metadata
     */
    CompletableFuture<List<VectorSearchResult>> query(
            @NotNull Object queryVector, 
            int topK, 
            VectorFilter filter);
    
    /**
     * Deletes a vector by ID.
     *
     * @param id the ID of the vector to delete
     * @return true if the vector was deleted, false if it didn't exist
     */
    CompletableFuture<Boolean> delete(@NotNull String id);
    
    /**
     * Deletes multiple vectors by IDs.
     *
     * @param ids the IDs of the vectors to delete
     * @return the number of vectors that were deleted
     */
    CompletableFuture<Integer> deleteBatch(@NotNull List<String> ids);
    
    /**
     * Batch deletes entity embeddings by entity names.
     * 
     * Used for document deletion and entity merge cleanup.
     * Deletes vectors where type='entity' and content matches any entity name.
     *
     * @param projectId the project UUID
     * @param entityNames set of entity names to delete embeddings for
     * @return a CompletableFuture<Integer> - number of embeddings deleted
     * @since spec-007
     */
    CompletableFuture<Integer> deleteEntityEmbeddings(@NotNull String projectId, @NotNull java.util.Set<String> entityNames);
    
    /**
     * Batch deletes chunk embeddings by chunk IDs.
     * 
     * Used for document deletion to remove chunk vectors.
     * Deletes vectors where type='chunk' and id matches any chunk ID.
     *
     * @param projectId the project UUID
     * @param chunkIds set of chunk IDs to delete embeddings for
     * @return a CompletableFuture<Integer> - number of embeddings deleted
     * @since spec-007
     */
    CompletableFuture<Integer> deleteChunkEmbeddings(@NotNull String projectId, @NotNull java.util.Set<String> chunkIds);
    
    /**
     * Gets all chunk IDs belonging to a document.
     * 
     * Used for document deletion to identify all chunks that need to be removed
     * along with their associated entities and relations.
     *
     * @param projectId the project UUID
     * @param documentId the document UUID
     * @return a CompletableFuture<List<String>> - list of chunk IDs belonging to the document
     * @since spec-007
     */
    CompletableFuture<List<String>> getChunkIdsByDocumentId(@NotNull String projectId, @NotNull String documentId);
    
    /**
     * Gets a vector by ID.
     *
     * @param id the ID of the vector to retrieve
     * @return the vector entry, or null if not found
     */
    CompletableFuture<VectorEntry> get(@NotNull String id);
    
    /**
     * Checks if a document has any vectors stored.
     * Used to prevent duplicate processing and detect race conditions.
     *
     * @param documentId the document UUID
     * @return true if the document has vectors, false otherwise
     */
    CompletableFuture<Boolean> hasVectors(@NotNull String documentId);
    
    /**
     * Clears all vectors from the storage.
     */
    CompletableFuture<Void> clear();
    
    /**
     * Gets the number of vectors in the storage.
     *
     * @return the number of vectors
     */
    CompletableFuture<Long> size();
    
    /**
     * Closes the storage and releases resources.
     */
    @Override
    void close() throws Exception;
    
    /**
     * Represents a vector entry with ID, vector data, and metadata.
     */
    record VectorEntry(
            @NotNull String id,
            @NotNull Object vector,
            @NotNull VectorMetadata metadata) {
    }
    
    /**
     * Represents metadata associated with a vector.
     */
    record VectorMetadata(
            @NotNull String type,
            @NotNull String content,
            String documentId,
            Integer chunkIndex,
            String projectId) {
    }
    
    /**
     * Represents a filter for vector queries.
     * 
     * @param type the type of vectors to filter (e.g., "chunk", "entity", "relation")
     * @param ids specific vector IDs to filter by
     * @param projectId the project UUID to filter vectors by (for multi-tenancy isolation)
     */
    record VectorFilter(
            String type, 
            List<String> ids, 
            @NotNull String projectId) {
    }
    
    /**
     * Represents a search result from a vector query.
     */
    record VectorSearchResult(
            @NotNull String id,
            double score,
            @NotNull VectorMetadata metadata) {
    }
}
