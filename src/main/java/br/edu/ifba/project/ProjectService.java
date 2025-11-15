package br.edu.ifba.project;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.storage.GraphStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ProjectService {

    private static final Logger LOG = Logger.getLogger(ProjectService.class);

    @Inject
    ProjectRepository projectRepository;

    @Inject
    GraphStorage graphStorage;

    @Transactional
    public Project create(final Project project) {
        projectRepository.persist(project);
        
        // Create isolated graph for the project
        final String projectId = project.getId().toString();
        try {
            graphStorage.createProjectGraph(projectId).join();
            LOG.infof("Created graph for project: %s", projectId);
        } catch (final CompletionException e) {
            LOG.errorf(e, "Failed to create graph for project: %s", projectId);
            throw new IllegalStateException("Failed to create project graph", e.getCause());
        }
        
        return project;
    }

    public Project findById(final UUID id) {
        return projectRepository.findByIdOrThrow(id);
    }

    public List<Project> findAll() {
        return projectRepository.listAll();
    }

    @Transactional
    public Project update(final UUID id, final String name) {
        final Project project = projectRepository.findByIdOrThrow(id);
        project.setName(name);
        return project;
    }

    @Transactional
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
        projectRepository.delete(project);
        projectRepository.flush();
        LOG.infof("Deleted project: %s", projectId);
    }
}
