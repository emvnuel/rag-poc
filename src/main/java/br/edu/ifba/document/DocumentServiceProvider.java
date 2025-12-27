package br.edu.ifba.document;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.deletion.DocumentDeletionService;
import br.edu.ifba.lightrag.deletion.KnowledgeRebuildResult;
import br.edu.ifba.lightrag.storage.impl.SQLiteConnectionManager;
import br.edu.ifba.project.Project;
import br.edu.ifba.project.ProjectRepositoryPort;
import br.edu.ifba.shared.UuidUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer that selects the appropriate DocumentServicePort implementation
 * based on runtime configuration.
 * 
 * <p>
 * This solves the issue where @IfBuildProperty is a build-time annotation
 * that doesn't work when switching profiles at runtime in dev mode.
 * </p>
 */
@ApplicationScoped
public class DocumentServiceProvider {

    private static final Logger LOG = Logger.getLogger(DocumentServiceProvider.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ConfigProperty(name = "lightrag.storage.backend", defaultValue = "postgresql")
    String storageBackend;

    @Inject
    DocumentDeletionService documentDeletionService;

    @Inject
    Instance<SQLiteConnectionManager> sqliteConnectionManager;

    @Inject
    @HibernateDocument
    Instance<DocumentRepository> hibernateRepository;

    @Inject
    Instance<ProjectRepositoryPort> projectRepository;

    // Cache the repository instance to share between service and repository
    // producers
    private DocumentRepositoryPort cachedRepository;

    /**
     * Produces the appropriate DocumentRepositoryPort based on storage backend
     * configuration.
     * This is needed for classes that inject DocumentRepositoryPort directly (e.g.,
     * DocumentProcessorJob, SearchService).
     * 
     * @return DocumentRepositoryPort implementation for the configured backend
     */
    @Produces
    @ApplicationScoped
    public DocumentRepositoryPort produceDocumentRepository() {
        if (cachedRepository != null) {
            return cachedRepository;
        }

        LOG.infof("Selecting DocumentRepositoryPort for backend: %s", storageBackend);

        if ("sqlite".equalsIgnoreCase(storageBackend)) {
            LOG.info("Using SQLite document repository");
            if (!sqliteConnectionManager.isResolvable()) {
                throw new IllegalStateException("SQLite backend selected but SQLiteConnectionManager not available");
            }
            if (!projectRepository.isResolvable()) {
                throw new IllegalStateException("SQLite backend selected but ProjectRepositoryPort not available");
            }
            cachedRepository = new RuntimeSQLiteDocumentRepository(
                    sqliteConnectionManager.get(),
                    projectRepository.get());
        } else {
            LOG.info("Using PostgreSQL document repository (Hibernate)");
            if (!hibernateRepository.isResolvable()) {
                throw new IllegalStateException("PostgreSQL backend selected but Hibernate repository not available");
            }
            cachedRepository = hibernateRepository.get();
        }
        return cachedRepository;
    }

    /**
     * Produces the appropriate DocumentServicePort based on storage backend
     * configuration.
     * 
     * @return DocumentServicePort implementation for the configured backend
     */
    @Produces
    @ApplicationScoped
    public DocumentServicePort produceDocumentService() {
        LOG.infof("Selecting DocumentServicePort for backend: %s", storageBackend);

        // Get or create the repository
        DocumentRepositoryPort repository = produceDocumentRepository();

        if ("sqlite".equalsIgnoreCase(storageBackend)) {
            LOG.info("Using SQLite document service");
            return new RuntimeSQLiteDocumentService(repository, documentDeletionService);
        } else {
            LOG.info("Using PostgreSQL document service (Hibernate)");
            return new RuntimePostgresDocumentService(repository, documentDeletionService);
        }
    }

    /**
     * Runtime SQLite document repository (embedded to avoid @IfBuildProperty
     * issues).
     */
    private static class RuntimeSQLiteDocumentRepository implements DocumentRepositoryPort {
        private static final Logger LOG = Logger.getLogger(RuntimeSQLiteDocumentRepository.class);

        private final SQLiteConnectionManager connectionManager;
        private final ProjectRepositoryPort projectRepository;

        RuntimeSQLiteDocumentRepository(SQLiteConnectionManager connectionManager,
                ProjectRepositoryPort projectRepository) {
            this.connectionManager = connectionManager;
            this.projectRepository = projectRepository;
        }

        @Override
        public void save(final Document document) {
            if (document.getId() == null) {
                document.setId(UuidUtils.randomV7());
            }

            final String sql = """
                    INSERT INTO documents (id, project_id, type, status, file_name, content, metadata, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                    ON CONFLICT(id) DO UPDATE SET
                        type = excluded.type,
                        status = excluded.status,
                        file_name = excluded.file_name,
                        content = excluded.content,
                        metadata = excluded.metadata,
                        updated_at = datetime('now')
                    """;

            Connection conn = null;
            try {
                conn = connectionManager.getWriteConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, document.getId().toString());
                    stmt.setString(2, document.getProject().getId().toString());
                    stmt.setString(3, document.getType().name());
                    stmt.setString(4, document.getStatus().name());
                    stmt.setString(5, document.getFileName());
                    stmt.setString(6, document.getContent());
                    stmt.setString(7, document.getMetadata());
                    stmt.executeUpdate();
                    LOG.debugf("Saved document: %s", document.getId());
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save document: " + document.getId(), e);
            } finally {
                if (conn != null) {
                    connectionManager.releaseWriteConnection(conn);
                }
            }
        }

        @Override
        public Optional<Document> findDocumentById(final UUID id) {
            final String sql = """
                    SELECT id, project_id, type, status, file_name, content, metadata, created_at, updated_at
                    FROM documents WHERE id = ?
                    """;

            try (Connection conn = connectionManager.getReadConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRowToDocument(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to find document by id: " + id, e);
            }
            return Optional.empty();
        }

        @Override
        public Document findByIdOrThrow(final UUID id) {
            return findDocumentById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found with id: " + id));
        }

        @Override
        public Document findByFileName(final String fileName) {
            final String sql = """
                    SELECT id, project_id, type, status, file_name, content, metadata, created_at, updated_at
                    FROM documents WHERE file_name = ?
                    """;

            try (Connection conn = connectionManager.getReadConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, fileName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapRowToDocument(rs);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to find document by file name: " + fileName, e);
            }
            return null;
        }

        @Override
        public List<Document> findByProjectId(final UUID projectId) {
            final String sql = """
                    SELECT id, project_id, type, status, file_name, content, metadata, created_at, updated_at
                    FROM documents WHERE project_id = ? ORDER BY created_at DESC
                    """;
            final List<Document> documents = new ArrayList<>();

            try (Connection conn = connectionManager.getReadConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        documents.add(mapRowToDocument(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to find documents by project id: " + projectId, e);
            }
            return documents;
        }

        @Override
        public List<Document> findNotProcessed(final int limit) {
            final String sql = """
                    SELECT id, project_id, type, status, file_name, content, metadata, created_at, updated_at
                    FROM documents WHERE status = 'NOT_PROCESSED'
                    ORDER BY created_at ASC LIMIT ?
                    """;
            final List<Document> documents = new ArrayList<>();

            try (Connection conn = connectionManager.getReadConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        documents.add(mapRowToDocument(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to find not processed documents", e);
            }
            return documents;
        }

        @Override
        public List<Document> findNotProcessedWithLock(final int limit) {
            // SQLite doesn't support row-level locking like PostgreSQL
            // For SQLite, we rely on the connection-level write lock
            return findNotProcessed(limit);
        }

        @Override
        public void deleteDocument(final Document document) {
            final String sql = "DELETE FROM documents WHERE id = ?";

            Connection conn = null;
            try {
                conn = connectionManager.getWriteConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, document.getId().toString());
                    final int rows = stmt.executeUpdate();
                    if (rows > 0) {
                        LOG.debugf("Deleted document: %s", document.getId());
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete document: " + document.getId(), e);
            } finally {
                if (conn != null) {
                    connectionManager.releaseWriteConnection(conn);
                }
            }
        }

        @Override
        public void update(final Document document) {
            final String sql = """
                    UPDATE documents SET
                        type = ?, status = ?, file_name = ?, content = ?, metadata = ?, updated_at = datetime('now')
                    WHERE id = ?
                    """;

            Connection conn = null;
            try {
                conn = connectionManager.getWriteConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, document.getType().name());
                    stmt.setString(2, document.getStatus().name());
                    stmt.setString(3, document.getFileName());
                    stmt.setString(4, document.getContent());
                    stmt.setString(5, document.getMetadata());
                    stmt.setString(6, document.getId().toString());
                    stmt.executeUpdate();
                    LOG.debugf("Updated document: %s", document.getId());
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update document: " + document.getId(), e);
            } finally {
                if (conn != null) {
                    connectionManager.releaseWriteConnection(conn);
                }
            }
        }

        @Override
        public void flush() {
            // No-op for SQLite - changes are committed immediately
        }

        @Override
        public List<Document> findByStatus(final DocumentStatus status) {
            final String sql = """
                    SELECT id, project_id, type, status, file_name, content, metadata, created_at, updated_at
                    FROM documents WHERE status = ? ORDER BY created_at ASC
                    """;
            final List<Document> documents = new ArrayList<>();

            try (Connection conn = connectionManager.getReadConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, status.name());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        documents.add(mapRowToDocument(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to find documents by status: " + status, e);
            }
            return documents;
        }

        @Override
        public long countByProjectId(final UUID projectId) {
            final String sql = "SELECT COUNT(*) FROM documents WHERE project_id = ?";

            try (Connection conn = connectionManager.getReadConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to count documents by project id: " + projectId, e);
            }
            return 0;
        }

        private Document mapRowToDocument(final ResultSet rs) throws SQLException {
            final UUID projectId = UUID.fromString(rs.getString("project_id"));
            final Project project = projectRepository.findByIdOrThrow(projectId);

            final DocumentType type = DocumentType.valueOf(rs.getString("type"));
            final String fileName = rs.getString("file_name");
            final String content = rs.getString("content");
            final String metadata = rs.getString("metadata");

            final Document document = new Document(type, fileName, content, metadata, project);
            document.setId(UUID.fromString(rs.getString("id")));
            document.setStatus(DocumentStatus.valueOf(rs.getString("status")));

            final String createdAt = rs.getString("created_at");
            if (createdAt != null) {
                document.setCreatedAt(LocalDateTime.parse(createdAt, DATE_FORMAT));
            }

            final String updatedAt = rs.getString("updated_at");
            if (updatedAt != null) {
                document.setUpdatedAt(LocalDateTime.parse(updatedAt, DATE_FORMAT));
            }

            return document;
        }
    }

    /**
     * Runtime SQLite document service that doesn't use @Transactional.
     */
    private static class RuntimeSQLiteDocumentService implements DocumentServicePort {
        private static final Logger LOG = Logger.getLogger(RuntimeSQLiteDocumentService.class);

        private final DocumentRepositoryPort repository;
        private final DocumentDeletionService documentDeletionService;

        RuntimeSQLiteDocumentService(DocumentRepositoryPort repository,
                DocumentDeletionService documentDeletionService) {
            this.repository = repository;
            this.documentDeletionService = documentDeletionService;
        }

        @Override
        public Document create(final Document document) {
            repository.save(document);
            return document;
        }

        @Override
        public Document findById(final UUID id) {
            return repository.findByIdOrThrow(id);
        }

        @Override
        public void delete(final UUID documentId, final UUID projectId) {
            delete(documentId, projectId, false);
        }

        @Override
        public void delete(final UUID documentId, final UUID projectId, final boolean skipRebuild) {
            final Document document = repository.findByIdOrThrow(documentId);

            try {
                KnowledgeRebuildResult result = documentDeletionService
                        .deleteDocument(projectId, documentId, skipRebuild)
                        .join();

                LOG.infof(
                        "Document deletion completed (skipRebuild=%s) - entities deleted: %d, rebuilt: %d, relations deleted: %d, rebuilt: %d",
                        skipRebuild,
                        result.entitiesDeleted().size(),
                        result.entitiesRebuilt().size(),
                        result.relationsDeleted(),
                        result.relationsRebuilt());

                if (!result.errors().isEmpty()) {
                    LOG.warnf("Document deletion had %d errors: %s",
                            result.errors().size(),
                            String.join("; ", result.errors()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete graph data for document: " + documentId, e);
            }

            // Delete the document (vectors will cascade automatically via FK constraint)
            repository.deleteDocument(document);
        }

        @Override
        public Document findByFileName(final String fileName) {
            return repository.findByFileName(fileName);
        }

        @Override
        public List<Document> findByProjectId(final UUID projectId) {
            return repository.findByProjectId(projectId);
        }

        @Override
        public DocumentProgressResponse getProcessingProgress(final UUID documentId) {
            final Document document = repository.findByIdOrThrow(documentId);

            final double progressPercentage = switch (document.getStatus()) {
                case PROCESSED -> 100.0;
                case PROCESSING -> 50.0;
                case NOT_PROCESSED -> 0.0;
            };

            return new DocumentProgressResponse(progressPercentage);
        }
    }

    /**
     * Runtime PostgreSQL document service wrapper.
     * Uses the Hibernate repository but avoids @Transactional since we're in a
     * producer.
     */
    private static class RuntimePostgresDocumentService implements DocumentServicePort {
        private static final Logger LOG = Logger.getLogger(RuntimePostgresDocumentService.class);

        private final DocumentRepositoryPort repository;
        private final DocumentDeletionService documentDeletionService;

        RuntimePostgresDocumentService(DocumentRepositoryPort repository,
                DocumentDeletionService documentDeletionService) {
            this.repository = repository;
            this.documentDeletionService = documentDeletionService;
        }

        @Override
        public Document create(final Document document) {
            repository.save(document);
            return document;
        }

        @Override
        public Document findById(final UUID id) {
            return repository.findByIdOrThrow(id);
        }

        @Override
        public void delete(final UUID documentId, final UUID projectId) {
            delete(documentId, projectId, false);
        }

        @Override
        public void delete(final UUID documentId, final UUID projectId, final boolean skipRebuild) {
            final Document document = repository.findByIdOrThrow(documentId);

            try {
                KnowledgeRebuildResult result = documentDeletionService
                        .deleteDocument(projectId, documentId, skipRebuild)
                        .join();

                LOG.infof(
                        "Document deletion completed (skipRebuild=%s) - entities deleted: %d, rebuilt: %d, relations deleted: %d, rebuilt: %d",
                        skipRebuild,
                        result.entitiesDeleted().size(),
                        result.entitiesRebuilt().size(),
                        result.relationsDeleted(),
                        result.relationsRebuilt());

                if (!result.errors().isEmpty()) {
                    LOG.warnf("Document deletion had %d errors: %s",
                            result.errors().size(),
                            String.join("; ", result.errors()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete graph data for document: " + documentId, e);
            }

            repository.deleteDocument(document);
        }

        @Override
        public Document findByFileName(final String fileName) {
            return repository.findByFileName(fileName);
        }

        @Override
        public List<Document> findByProjectId(final UUID projectId) {
            return repository.findByProjectId(projectId);
        }

        @Override
        public DocumentProgressResponse getProcessingProgress(final UUID documentId) {
            final Document document = repository.findByIdOrThrow(documentId);

            final double progressPercentage = switch (document.getStatus()) {
                case PROCESSED -> 100.0;
                case PROCESSING -> 50.0;
                case NOT_PROCESSED -> 0.0;
            };

            return new DocumentProgressResponse(progressPercentage);
        }
    }
}
