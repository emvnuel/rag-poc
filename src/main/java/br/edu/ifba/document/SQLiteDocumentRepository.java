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

import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.storage.impl.SQLiteConnectionManager;
import br.edu.ifba.project.Project;
import br.edu.ifba.project.ProjectRepositoryPort;
import br.edu.ifba.shared.UuidUtils;
import jakarta.inject.Inject;

/**
 * SQLite implementation of DocumentRepositoryPort.
 * 
 * <p>
 * <b>Note:</b> This class is now deprecated in favor of the embedded
 * RuntimeSQLiteDocumentRepository in DocumentServiceProvider, which handles
 * runtime selection between PostgreSQL and SQLite backends.
 * The @IfBuildProperty annotation was removed because it's a build-time
 * annotation
 * that doesn't work when switching profiles at runtime in dev mode.
 * </p>
 * 
 * @deprecated Use DocumentServiceProvider instead for runtime backend selection
 */
public class SQLiteDocumentRepository implements DocumentRepositoryPort {

    private static final Logger LOG = Logger.getLogger(SQLiteDocumentRepository.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    SQLiteConnectionManager connectionManager;

    @Inject
    ProjectRepositoryPort projectRepository;

    @Override
    public void save(final Document document) {
        // Generate UUID if not set
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
        // In a real scenario, you might want to use a transaction and update status
        // immediately
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
