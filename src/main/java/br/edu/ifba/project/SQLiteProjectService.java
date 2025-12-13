package br.edu.ifba.project;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.storage.GraphStorage;
import jakarta.inject.Inject;

/**
 * SQLite implementation of project operations.
 * Does NOT use JTA transactions since SQLite is not JTA-compatible.
 * SQLite operations are auto-committed.
 * 
 * @deprecated Use ProjectServiceProvider instead which handles runtime backend selection.
 *             This class is kept for backwards compatibility but is no longer a CDI bean.
 */
// Removed: @ApplicationScoped
// Removed: @IfBuildProperty - now handled by ProjectServiceProvider
public class SQLiteProjectService {

    private static final Logger LOG = Logger.getLogger(SQLiteProjectService.class);

    @Inject
    ProjectRepositoryPort projectRepository;

    @Inject
    GraphStorage graphStorage;

    public Project create(final Project project) {
        // Save project first (generates ID if not set)
        projectRepository.save(project);
        
        // Create isolated graph for the project
        final String projectId = project.getId().toString();
        try {
            graphStorage.createProjectGraph(projectId).join();
            LOG.infof("Created graph for project: %s", projectId);
        } catch (final CompletionException e) {
            LOG.errorf(e, "Failed to create graph for project: %s", projectId);
            // Rollback: delete the project we just created
            try {
                projectRepository.deleteProject(project);
            } catch (Exception rollbackEx) {
                LOG.errorf(rollbackEx, "Failed to rollback project creation: %s", projectId);
            }
            throw new IllegalStateException("Failed to create project graph", e.getCause());
        }
        
        return project;
    }

    public Project findById(final UUID id) {
        return projectRepository.findByIdOrThrow(id);
    }

    public List<Project> findAll() {
        return projectRepository.findAllProjects();
    }

    public Project update(final UUID id, final String name) {
        final Project project = projectRepository.findByIdOrThrow(id);
        project.setName(name);
        projectRepository.save(project);
        return project;
    }

    public void delete(final UUID id) {
        final Project project = projectRepository.findByIdOrThrow(id);
        
        // Delete project's graph before deleting the project
        final String projectId = id.toString();
        try {
            graphStorage.deleteProjectGraph(projectId).join();
            LOG.infof("Deleted graph for project: %s", projectId);
        } catch (final CompletionException e) {
            LOG.errorf(e, "Failed to delete graph for project: %s", projectId);
            throw new IllegalStateException("Failed to delete project graph", e.getCause());
        }
        
        // Delete the project (this will cascade to documents and vectors via FK)
        projectRepository.deleteProject(project);
        LOG.infof("Deleted project: %s", projectId);
    }
}
