package br.edu.ifba.project;

import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ProjectRepository implements PanacheRepositoryBase<Project, UUID> {

    public Project findByIdOrThrow(final UUID id) {
        final Project project = findById(id);
        if (project == null) {
            throw new IllegalArgumentException("Project not found with id: " + id);
        }
        return project;
    }

    public Project findByName(final String name) {
        return find("name", name).firstResult();
    }
}
