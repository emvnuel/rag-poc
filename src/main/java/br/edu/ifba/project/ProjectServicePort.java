package br.edu.ifba.project;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Project operations.
 * Allows different implementations for PostgreSQL (with JTA) and SQLite (without JTA).
 */
public interface ProjectServicePort {

    /**
     * Creates a new project and its associated graph.
     *
     * @param project the project to create
     * @return the created project with generated ID
     */
    Project create(Project project);

    /**
     * Finds a project by ID.
     *
     * @param id the project ID
     * @return the project
     * @throws IllegalArgumentException if not found
     */
    Project findById(UUID id);

    /**
     * Returns all projects.
     *
     * @return list of all projects
     */
    List<Project> findAll();

    /**
     * Updates a project's name.
     *
     * @param id the project ID
     * @param name the new name
     * @return the updated project
     */
    Project update(UUID id, String name);

    /**
     * Deletes a project and its associated graph.
     *
     * @param id the project ID
     */
    void delete(UUID id);
}
