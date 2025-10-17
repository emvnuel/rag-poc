package br.edu.ifba.project;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ProjectService {

    @Inject
    ProjectRepository projectRepository;

    @Transactional
    public Project create(final Project project) {
        projectRepository.persist(project);
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
        projectRepository.delete(project);
    }
}
