package br.edu.ifba.lightrag.storage.impl;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;

/**
 * Validates storage backend configuration on application startup.
 * 
 * <p>This component ensures that:</p>
 * <ul>
 *   <li>Exactly one storage backend is configured and active</li>
 *   <li>The configured backend matches the available implementations</li>
 *   <li>Configuration is valid before the application starts processing</li>
 * </ul>
 * 
 * <p>Supported backends:</p>
 * <ul>
 *   <li><code>postgresql</code> - PostgreSQL with Apache AGE and pgvector (default)</li>
 *   <li><code>sqlite</code> - SQLite with sqlite-vec and sqlite-graph extensions</li>
 * </ul>
 */
@ApplicationScoped
@Startup
public class StorageBackendValidator {

    private static final Logger logger = LoggerFactory.getLogger(StorageBackendValidator.class);

    @ConfigProperty(name = "lightrag.storage.backend", defaultValue = "postgresql")
    String configuredBackend;

    @Inject
    Instance<VectorStorage> vectorStorageInstances;

    @Inject
    Instance<GraphStorage> graphStorageInstances;

    /**
     * Validates storage backend configuration on startup.
     * 
     * @param event the startup event
     * @throws IllegalStateException if backend configuration is invalid
     */
    void onStart(@Observes StartupEvent event) {
        logger.info("Validating storage backend configuration...");
        
        validateBackendName();
        validateActiveImplementations();
        
        logger.info("Storage backend validation complete: {} backend active", configuredBackend);
    }

    /**
     * Validates that the configured backend name is valid.
     */
    private void validateBackendName() {
        if (configuredBackend == null || configuredBackend.isBlank()) {
            throw new IllegalStateException(
                "Storage backend not configured. Set 'lightrag.storage.backend' to 'postgresql' or 'sqlite'");
        }

        String normalizedBackend = configuredBackend.toLowerCase().trim();
        if (!normalizedBackend.equals("postgresql") && !normalizedBackend.equals("sqlite")) {
            throw new IllegalStateException(
                "Invalid storage backend: '" + configuredBackend + "'. " +
                "Supported backends: 'postgresql', 'sqlite'");
        }
    }

    /**
     * Validates that storage implementations are available and unique.
     */
    private void validateActiveImplementations() {
        // Count active vector storage implementations
        long vectorCount = 0;
        String vectorImpl = null;
        for (VectorStorage vs : vectorStorageInstances) {
            vectorCount++;
            vectorImpl = vs.getClass().getSimpleName();
        }

        // Count active graph storage implementations
        long graphCount = 0;
        String graphImpl = null;
        for (GraphStorage gs : graphStorageInstances) {
            graphCount++;
            graphImpl = gs.getClass().getSimpleName();
        }

        // Log active implementations
        logger.info("Active storage implementations: VectorStorage={}, GraphStorage={}", 
            vectorImpl, graphImpl);

        // Validate vector storage
        if (vectorCount == 0) {
            logger.warn("No VectorStorage implementation found. " +
                "This may indicate a configuration issue with backend='{}'", configuredBackend);
        } else if (vectorCount > 1) {
            throw new IllegalStateException(
                "Multiple VectorStorage implementations found (" + vectorCount + "). " +
                "Ensure only one backend is configured via 'lightrag.storage.backend'");
        }

        // Validate graph storage
        if (graphCount == 0) {
            logger.warn("No GraphStorage implementation found. " +
                "This may indicate a configuration issue with backend='{}'", configuredBackend);
        } else if (graphCount > 1) {
            throw new IllegalStateException(
                "Multiple GraphStorage implementations found (" + graphCount + "). " +
                "Ensure only one backend is configured via 'lightrag.storage.backend'");
        }

        // Validate backend matches implementation
        validateBackendMatchesImplementation(vectorImpl, graphImpl);
    }

    /**
     * Validates that the configured backend matches the active implementations.
     */
    private void validateBackendMatchesImplementation(String vectorImpl, String graphImpl) {
        boolean isSqliteBackend = "sqlite".equals(configuredBackend.toLowerCase());
        boolean isPostgresBackend = "postgresql".equals(configuredBackend.toLowerCase());

        if (isSqliteBackend) {
            if (vectorImpl != null && !vectorImpl.contains("SQLite")) {
                logger.warn("Backend configured as 'sqlite' but VectorStorage is {}", vectorImpl);
            }
            if (graphImpl != null && !graphImpl.contains("SQLite")) {
                logger.warn("Backend configured as 'sqlite' but GraphStorage is {}", graphImpl);
            }
        } else if (isPostgresBackend) {
            if (vectorImpl != null && !vectorImpl.contains("Pg")) {
                logger.warn("Backend configured as 'postgresql' but VectorStorage is {}", vectorImpl);
            }
            if (graphImpl != null && !graphImpl.contains("Age")) {
                logger.warn("Backend configured as 'postgresql' but GraphStorage is {}", graphImpl);
            }
        }
    }

    /**
     * Returns the configured storage backend name.
     * 
     * @return the backend name (postgresql or sqlite)
     */
    public String getConfiguredBackend() {
        return configuredBackend;
    }

    /**
     * Returns true if SQLite backend is configured.
     * 
     * @return true if using SQLite backend
     */
    public boolean isSqliteBackend() {
        return "sqlite".equalsIgnoreCase(configuredBackend);
    }

    /**
     * Returns true if PostgreSQL backend is configured.
     * 
     * @return true if using PostgreSQL backend
     */
    public boolean isPostgresBackend() {
        return "postgresql".equalsIgnoreCase(configuredBackend);
    }
}
