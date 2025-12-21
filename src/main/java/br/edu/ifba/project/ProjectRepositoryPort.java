package br.edu.ifba.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository abstraction for Project persistence operations.
 * 
 * <p>
 * This interface allows switching between different persistence backends
 * (Hibernate/PostgreSQL or SQLite) via configuration.
 * </p>
 */
public interface ProjectRepositoryPort {

    /**
     * Saves a new project.
     * 
     * @param project the project to save
     */
    void save(Project project);

    /**
     * Finds a project by ID.
     * 
     * @param id the project ID
     * @return the project wrapped in Optional
     */
    Optional<Project> findProjectById(UUID id);

    /**
     * Finds a project by ID or throws if not found.
     * 
     * @param id the project ID
     * @return the project
     * @throws IllegalArgumentException if project not found
     */
    Project findByIdOrThrow(UUID id);

    /**
     * Finds a project by name.
     * 
     * @param name the project name
     * @return the project, or null if not found
     */
    Project findByName(String name);

    /**
     * Lists all projects.
     * 
     * @return list of all projects
     */
    List<Project> findAllProjects();

    /**
     * Finds all projects owned by the specified user.
     * 
     * @param ownerId the Keycloak subject ID of the owner
     * @return list of projects owned by the user
     */
    List<Project> findByOwnerId(String ownerId);

    /**
     * Deletes a project.
     * 
     * @param project the project to delete
     */
    void deleteProject(Project project);

    /**
     * Flushes pending changes to the database.
     */
    void flush();
}
