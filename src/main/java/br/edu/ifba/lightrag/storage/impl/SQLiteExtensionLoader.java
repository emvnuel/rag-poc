package br.edu.ifba.lightrag.storage.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.jboss.logging.Logger;

/**
 * Handles loading of native SQLite extensions (sqlite-vec, sqlite-graph).
 * 
 * <p>Supports loading from bundled resources or external paths. When loading
 * from resources, the native library is extracted to a temp directory and
 * loaded from there.</p>
 * 
 * <p>Platform detection is automatic based on OS and architecture.</p>
 */
public final class SQLiteExtensionLoader {

    private static final Logger LOG = Logger.getLogger(SQLiteExtensionLoader.class);

    private static final String VECTOR_EXTENSION_NAME = "sqlite-vec";
    private static final String GRAPH_EXTENSION_NAME = "sqlite-graph";
    private static final String VECTOR_LIB_BASE = "vector0";
    private static final String GRAPH_LIB_BASE = "libgraph";

    private final String externalPath;
    private Path extractedVectorPath;
    private Path extractedGraphPath;

    /**
     * Creates a loader that uses bundled extensions from classpath.
     */
    public SQLiteExtensionLoader() {
        this.externalPath = null;
    }

    /**
     * Creates a loader that uses extensions from an external directory.
     * 
     * @param externalPath path to directory containing native libraries
     */
    public SQLiteExtensionLoader(String externalPath) {
        this.externalPath = externalPath;
    }

    /**
     * Returns the current platform identifier.
     * 
     * @return platform string (e.g., "linux-x86_64", "darwin-aarch64")
     */
    public String getCurrentPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String osName;
        if (os.contains("linux")) {
            osName = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "darwin";
        } else if (os.contains("windows")) {
            osName = "windows";
        } else {
            osName = os.replaceAll("\\s+", "-");
        }

        String archName;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            archName = "x86_64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            archName = "aarch64";
        } else {
            archName = arch.replaceAll("\\s+", "-");
        }

        return osName + "-" + archName;
    }

    /**
     * Returns the expected file extension suffix for the current platform.
     * 
     * @return extension suffix (.so, .dylib, or .dll)
     */
    public String getExpectedExtensionSuffix() {
        String platform = getCurrentPlatform();
        if (platform.startsWith("darwin-")) {
            return ".dylib";
        } else if (platform.startsWith("windows-")) {
            return ".dll";
        } else {
            return ".so";
        }
    }

    /**
     * Checks if extensions are available for the current platform.
     * 
     * @return true if both vector and graph extensions are available
     */
    public boolean areExtensionsAvailable() {
        try {
            String platform = getCurrentPlatform();
            String suffix = getExpectedExtensionSuffix();

            if (externalPath != null) {
                Path platformDir = Path.of(externalPath, platform);
                Path vectorPath = platformDir.resolve(VECTOR_LIB_BASE + suffix);
                Path graphPath = platformDir.resolve(GRAPH_LIB_BASE + suffix);
                return Files.exists(vectorPath) && Files.exists(graphPath);
            } else {
                // Check classpath resources
                String vectorResource = "/native/" + platform + "/" + VECTOR_LIB_BASE + suffix;
                String graphResource = "/native/" + platform + "/" + GRAPH_LIB_BASE + suffix;
                return getClass().getResource(vectorResource) != null
                        && getClass().getResource(graphResource) != null;
            }
        } catch (Exception e) {
            LOG.debug("Error checking extension availability", e);
            return false;
        }
    }

    /**
     * Gets the path to the vector extension library.
     * 
     * @return absolute path to the vector extension
     * @throws SQLiteExtensionLoadException if extension cannot be found or extracted
     */
    public String getVectorExtensionPath() {
        String platform = getCurrentPlatform();
        String suffix = getExpectedExtensionSuffix();
        String libName = VECTOR_LIB_BASE + suffix;

        if (externalPath != null) {
            Path extPath = Path.of(externalPath, platform, libName);
            if (!Files.exists(extPath)) {
                throw new SQLiteExtensionLoadException(VECTOR_EXTENSION_NAME, platform, extPath.toString());
            }
            return extPath.toAbsolutePath().toString();
        }

        // Extract from classpath
        if (extractedVectorPath != null && Files.exists(extractedVectorPath)) {
            return extractedVectorPath.toAbsolutePath().toString();
        }

        extractedVectorPath = extractFromClasspath(platform, libName, VECTOR_EXTENSION_NAME);
        return extractedVectorPath.toAbsolutePath().toString();
    }

    /**
     * Gets the path to the graph extension library.
     * 
     * @return absolute path to the graph extension
     * @throws SQLiteExtensionLoadException if extension cannot be found or extracted
     */
    public String getGraphExtensionPath() {
        String platform = getCurrentPlatform();
        String suffix = getExpectedExtensionSuffix();
        String libName = GRAPH_LIB_BASE + suffix;

        if (externalPath != null) {
            Path extPath = Path.of(externalPath, platform, libName);
            if (!Files.exists(extPath)) {
                throw new SQLiteExtensionLoadException(GRAPH_EXTENSION_NAME, platform, extPath.toString());
            }
            return extPath.toAbsolutePath().toString();
        }

        // Extract from classpath
        if (extractedGraphPath != null && Files.exists(extractedGraphPath)) {
            return extractedGraphPath.toAbsolutePath().toString();
        }

        extractedGraphPath = extractFromClasspath(platform, libName, GRAPH_EXTENSION_NAME);
        return extractedGraphPath.toAbsolutePath().toString();
    }

    /**
     * Loads all extensions into the given connection.
     * 
     * @param conn JDBC Connection with extension loading enabled
     * @throws SQLException if extension loading fails
     */
    public void loadAllExtensions(Connection conn) throws SQLException {
        loadVectorExtension(conn);
        loadGraphExtension(conn);
    }

    /**
     * Loads the vector extension into the given connection.
     * 
     * @param conn JDBC Connection
     * @throws SQLException if loading fails
     */
    public void loadVectorExtension(Connection conn) throws SQLException {
        String path = getVectorExtensionPath();
        loadExtension(conn, path, VECTOR_EXTENSION_NAME);
    }

    /**
     * Loads the graph extension into the given connection.
     * 
     * @param conn JDBC Connection
     * @throws SQLException if loading fails
     */
    public void loadGraphExtension(Connection conn) throws SQLException {
        String path = getGraphExtensionPath();
        loadExtension(conn, path, GRAPH_EXTENSION_NAME);
    }

    private void loadExtension(Connection conn, String path, String name) throws SQLException {
        LOG.debugf("Loading extension %s from %s", name, path);
        try (Statement stmt = conn.createStatement()) {
            // Remove file extension for SQLite load_extension
            String loadPath = path;
            if (loadPath.endsWith(".so") || loadPath.endsWith(".dylib") || loadPath.endsWith(".dll")) {
                loadPath = loadPath.substring(0, loadPath.lastIndexOf('.'));
            }
            stmt.execute("SELECT load_extension('" + loadPath + "')");
            LOG.infof("Loaded SQLite extension: %s", name);
        } catch (SQLException e) {
            String platform = getCurrentPlatform();
            throw new SQLiteExtensionLoadException(name, platform, path, e);
        }
    }

    private Path extractFromClasspath(String platform, String libName, String extensionName) {
        String resourcePath = "/native/" + platform + "/" + libName;
        InputStream is = getClass().getResourceAsStream(resourcePath);

        if (is == null) {
            throw new SQLiteExtensionLoadException(extensionName, platform,
                    "classpath:" + resourcePath);
        }

        try {
            Path tempDir = Files.createTempDirectory("sqlite-ext-");
            Path targetPath = tempDir.resolve(libName);

            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            targetPath.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();

            LOG.debugf("Extracted %s to %s", extensionName, targetPath);
            return targetPath;
        } catch (IOException e) {
            throw new SQLiteExtensionLoadException(extensionName, platform,
                    "classpath:" + resourcePath, e);
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
                // Ignore close errors
            }
        }
    }
}
