package br.edu.ifba.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hibernate/PostgreSQL implementation of ProjectRepositoryPort.
 * Active when lightrag.storage.backend=postgresql or when property is missing (default).
 */
@ApplicationScoped
@IfBuildProperty(name = "lightrag.storage.backend", stringValue = "postgresql", enableIfMissing = true)
public class ProjectRepository implements PanacheRepositoryBase<Project, UUID>, ProjectRepositoryPort {

    @Override
    public void save(final Project project) {
        persist(project);
    }

    @Override
    public Optional<Project> findProjectById(final UUID id) {
        return Optional.ofNullable(findById(id));
    }

    @Override
    public Project findByIdOrThrow(final UUID id) {
        final Project project = findById(id);
        if (project == null) {
            throw new IllegalArgumentException("Project not found with id: " + id);
        }
        return project;
    }

    @Override
    public Project findByName(final String name) {
        return find("name", name).firstResult();
    }

    @Override
    public List<Project> findAllProjects() {
        return listAll();
    }

    @Override
    public void deleteProject(final Project project) {
        delete(project);
    }

    @Override
    public void flush() {
        getEntityManager().flush();
    }
}
