package br.edu.ifba.project;

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
import br.edu.ifba.shared.UuidUtils;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * SQLite implementation of ProjectRepositoryPort.
 * Active when lightrag.storage.backend=sqlite.
 */
@ApplicationScoped
@IfBuildProperty(name = "lightrag.storage.backend", stringValue = "sqlite")
public class SQLiteProjectRepository implements ProjectRepositoryPort {

    private static final Logger LOG = Logger.getLogger(SQLiteProjectRepository.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    SQLiteConnectionManager connectionManager;

    @Override
    public void save(final Project project) {
        // Generate UUID if not set
        if (project.getId() == null) {
            project.setId(UuidUtils.randomV7());
        }

        final String sql = """
                INSERT INTO projects (id, name, owner_id, created_at, updated_at)
                VALUES (?, ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    owner_id = excluded.owner_id,
                    updated_at = datetime('now')
                """;

        Connection conn = null;
        try {
            conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, project.getId().toString());
                stmt.setString(2, project.getName());
                stmt.setString(3, project.getOwnerId());
                stmt.executeUpdate();
                LOG.debugf("Saved project: %s", project.getId());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save project: " + project.getId(), e);
        } finally {
            if (conn != null) {
                connectionManager.releaseWriteConnection(conn);
            }
        }
    }

    @Override
    public Optional<Project> findProjectById(final UUID id) {
        final String sql = "SELECT id, name, owner_id, created_at, updated_at FROM projects WHERE id = ?";

        try (Connection conn = connectionManager.getReadConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToProject(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find project by id: " + id, e);
        }
        return Optional.empty();
    }

    @Override
    public Project findByIdOrThrow(final UUID id) {
        return findProjectById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found with id: " + id));
    }

    @Override
    public Project findByName(final String name) {
        final String sql = "SELECT id, name, owner_id, created_at, updated_at FROM projects WHERE name = ?";

        try (Connection conn = connectionManager.getReadConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRowToProject(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find project by name: " + name, e);
        }
        return null;
    }

    @Override
    public List<Project> findAllProjects() {
        final String sql = "SELECT id, name, owner_id, created_at, updated_at FROM projects ORDER BY created_at DESC";
        final List<Project> projects = new ArrayList<>();

        try (Connection conn = connectionManager.getReadConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                projects.add(mapRowToProject(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list all projects", e);
        }
        return projects;
    }

    @Override
    public List<Project> findByOwnerId(final String ownerId) {
        final String sql = "SELECT id, name, owner_id, created_at, updated_at FROM projects WHERE owner_id = ? ORDER BY created_at DESC";
        final List<Project> projects = new ArrayList<>();

        try (Connection conn = connectionManager.getReadConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ownerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    projects.add(mapRowToProject(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find projects by owner: " + ownerId, e);
        }
        return projects;
    }

    @Override
    public void deleteProject(final Project project) {
        final String sql = "DELETE FROM projects WHERE id = ?";

        Connection conn = null;
        try {
            conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, project.getId().toString());
                final int rows = stmt.executeUpdate();
                if (rows > 0) {
                    LOG.debugf("Deleted project: %s", project.getId());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete project: " + project.getId(), e);
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

    private Project mapRowToProject(final ResultSet rs) throws SQLException {
        final Project project = new Project(rs.getString("name"));
        project.setId(UUID.fromString(rs.getString("id")));
        project.setOwnerId(rs.getString("owner_id"));

        final String createdAt = rs.getString("created_at");
        if (createdAt != null) {
            project.setCreatedAt(LocalDateTime.parse(createdAt, DATE_FORMAT));
        }

        final String updatedAt = rs.getString("updated_at");
        if (updatedAt != null) {
            project.setUpdatedAt(LocalDateTime.parse(updatedAt, DATE_FORMAT));
        }

        return project;
    }
}
