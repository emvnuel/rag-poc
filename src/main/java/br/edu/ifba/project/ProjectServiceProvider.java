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

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.impl.SQLiteConnectionManager;
import br.edu.ifba.shared.UuidUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer that selects the appropriate ProjectServicePort implementation
 * based on runtime configuration.
 * 
 * <p>This solves the issue where @IfBuildProperty is a build-time annotation
 * that doesn't work when switching profiles at runtime in dev mode.</p>
 */
@ApplicationScoped
public class ProjectServiceProvider {

    private static final Logger LOG = Logger.getLogger(ProjectServiceProvider.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ConfigProperty(name = "lightrag.storage.backend", defaultValue = "postgresql")
    String storageBackend;

    @Inject
    GraphStorage graphStorage;

    @Inject
    Instance<SQLiteConnectionManager> sqliteConnectionManager;

    @Inject
    Instance<ProjectRepository> hibernateRepository;

    /**
     * Produces the appropriate ProjectServicePort based on storage backend configuration.
     * 
     * @return ProjectServicePort implementation for the configured backend
     */
    @Produces
    @ApplicationScoped
    public ProjectServicePort produceProjectService() {
        LOG.infof("Selecting ProjectServicePort for backend: %s", storageBackend);

        if ("sqlite".equalsIgnoreCase(storageBackend)) {
            LOG.info("Using SQLite project service");
            if (!sqliteConnectionManager.isResolvable()) {
                throw new IllegalStateException("SQLite backend selected but SQLiteConnectionManager not available");
            }
            return new RuntimeSQLiteProjectService(
                new RuntimeSQLiteProjectRepository(sqliteConnectionManager.get()),
                graphStorage
            );
        } else {
            LOG.info("Using PostgreSQL project service (Hibernate)");
            if (!hibernateRepository.isResolvable()) {
                throw new IllegalStateException("PostgreSQL backend selected but Hibernate repository not available");
            }
            return new RuntimePostgresProjectService(hibernateRepository.get(), graphStorage);
        }
    }

    /**
     * Runtime SQLite project repository (embedded to avoid @IfBuildProperty issues).
     */
    private static class RuntimeSQLiteProjectRepository implements ProjectRepositoryPort {
        private final SQLiteConnectionManager connectionManager;

        RuntimeSQLiteProjectRepository(SQLiteConnectionManager connectionManager) {
            this.connectionManager = connectionManager;
        }

        @Override
        public void save(final Project project) {
            if (project.getId() == null) {
                project.setId(UuidUtils.randomV7());
            }
            
            final String sql = """
                INSERT INTO projects (id, name, created_at, updated_at)
                VALUES (?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    updated_at = datetime('now')
                """;

            Connection conn = null;
            try {
                conn = connectionManager.getWriteConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, project.getId().toString());
                    stmt.setString(2, project.getName());
                    stmt.executeUpdate();
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
            final String sql = "SELECT id, name, created_at, updated_at FROM projects WHERE id = ?";

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
            final String sql = "SELECT id, name, created_at, updated_at FROM projects WHERE name = ?";

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
            final String sql = "SELECT id, name, created_at, updated_at FROM projects ORDER BY created_at DESC";
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
        public void deleteProject(final Project project) {
            final String sql = "DELETE FROM projects WHERE id = ?";

            Connection conn = null;
            try {
                conn = connectionManager.getWriteConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, project.getId().toString());
                    stmt.executeUpdate();
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
            // No-op for SQLite
        }

        private Project mapRowToProject(final ResultSet rs) throws SQLException {
            final Project project = new Project(rs.getString("name"));
            project.setId(UUID.fromString(rs.getString("id")));
            
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

    /**
     * Runtime SQLite project service that doesn't use @Transactional.
     */
    private static class RuntimeSQLiteProjectService implements ProjectServicePort {
        private static final Logger LOG = Logger.getLogger(RuntimeSQLiteProjectService.class);
        
        private final ProjectRepositoryPort repository;
        private final GraphStorage graphStorage;

        RuntimeSQLiteProjectService(ProjectRepositoryPort repository, GraphStorage graphStorage) {
            this.repository = repository;
            this.graphStorage = graphStorage;
        }

        @Override
        public Project create(Project project) {
            repository.save(project);
            String projectId = project.getId().toString();
            try {
                graphStorage.createProjectGraph(projectId).join();
                LOG.infof("Created graph for project: %s", projectId);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create graph for project: %s", projectId);
                try {
                    repository.deleteProject(project);
                } catch (Exception rollbackEx) {
                    LOG.errorf(rollbackEx, "Failed to rollback project creation: %s", projectId);
                }
                throw new IllegalStateException("Failed to create project graph", e);
            }
            return project;
        }

        @Override
        public Project findById(UUID id) {
            return repository.findByIdOrThrow(id);
        }

        @Override
        public List<Project> findAll() {
            return repository.findAllProjects();
        }

        @Override
        public Project update(UUID id, String name) {
            Project project = repository.findByIdOrThrow(id);
            project.setName(name);
            repository.save(project);
            return project;
        }

        @Override
        public void delete(UUID id) {
            Project project = repository.findByIdOrThrow(id);
            String projectId = id.toString();
            try {
                graphStorage.deleteProjectGraph(projectId).join();
                LOG.infof("Deleted graph for project: %s", projectId);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete graph for project: %s", projectId);
                throw new IllegalStateException("Failed to delete project graph", e);
            }
            repository.deleteProject(project);
            LOG.infof("Deleted project: %s", projectId);
        }
    }

    /**
     * Runtime PostgreSQL project service wrapper.
     */
    private static class RuntimePostgresProjectService implements ProjectServicePort {
        private static final Logger LOG = Logger.getLogger(RuntimePostgresProjectService.class);
        
        private final ProjectRepositoryPort repository;
        private final GraphStorage graphStorage;

        RuntimePostgresProjectService(ProjectRepositoryPort repository, GraphStorage graphStorage) {
            this.repository = repository;
            this.graphStorage = graphStorage;
        }

        @Override
        public Project create(Project project) {
            repository.save(project);
            String projectId = project.getId().toString();
            try {
                graphStorage.createProjectGraph(projectId).join();
                LOG.infof("Created graph for project: %s", projectId);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create graph for project: %s", projectId);
                throw new IllegalStateException("Failed to create project graph", e);
            }
            return project;
        }

        @Override
        public Project findById(UUID id) {
            return repository.findByIdOrThrow(id);
        }

        @Override
        public List<Project> findAll() {
            return repository.findAllProjects();
        }

        @Override
        public Project update(UUID id, String name) {
            Project project = repository.findByIdOrThrow(id);
            project.setName(name);
            repository.save(project);
            return project;
        }

        @Override
        public void delete(UUID id) {
            Project project = repository.findByIdOrThrow(id);
            String projectId = id.toString();
            try {
                graphStorage.deleteProjectGraph(projectId).join();
                LOG.infof("Deleted graph for project: %s", projectId);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete graph for project: %s", projectId);
                throw new IllegalStateException("Failed to delete project graph", e);
            }
            repository.deleteProject(project);
            repository.flush();
            LOG.infof("Deleted project: %s", projectId);
        }
    }
}
