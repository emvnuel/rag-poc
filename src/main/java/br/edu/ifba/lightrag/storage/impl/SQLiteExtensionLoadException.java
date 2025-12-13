package br.edu.ifba.lightrag.storage.impl;

/**
 * Exception thrown when SQLite extension loading fails.
 * 
 * <p>This exception is thrown when the application cannot load required
 * SQLite extensions (sqlite-vec for vector operations or sqlite-graph
 * for graph operations) from the filesystem.</p>
 * 
 * <p>Common causes include:</p>
 * <ul>
 *   <li>Extension file not found at the configured path</li>
 *   <li>Extension file incompatible with current platform</li>
 *   <li>Insufficient permissions to load the extension</li>
 *   <li>Extension compiled for different SQLite version</li>
 * </ul>
 */
public final class SQLiteExtensionLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String extensionName;
    private final String platform;
    private final String extensionPath;

    /**
     * Creates a new SQLiteExtensionLoadException.
     * 
     * @param extensionName the name of the extension that failed to load
     * @param platform the current platform (e.g., "linux-x86_64", "darwin-aarch64")
     * @param extensionPath the path where the extension was expected
     * @param cause the underlying exception
     */
    public SQLiteExtensionLoadException(String extensionName, String platform, 
            String extensionPath, Throwable cause) {
        super(buildMessage(extensionName, platform, extensionPath), cause);
        this.extensionName = extensionName;
        this.platform = platform;
        this.extensionPath = extensionPath;
    }

    /**
     * Creates a new SQLiteExtensionLoadException without a cause.
     * 
     * @param extensionName the name of the extension that failed to load
     * @param platform the current platform
     * @param extensionPath the path where the extension was expected
     */
    public SQLiteExtensionLoadException(String extensionName, String platform, 
            String extensionPath) {
        super(buildMessage(extensionName, platform, extensionPath));
        this.extensionName = extensionName;
        this.platform = platform;
        this.extensionPath = extensionPath;
    }

    private static String buildMessage(String extensionName, String platform, String extensionPath) {
        return String.format(
            "Failed to load SQLite extension '%s' for platform '%s'. " +
            "Expected at: %s. Please ensure the native library is available " +
            "and compatible with your system.",
            extensionName, platform, extensionPath
        );
    }

    /**
     * Returns the name of the extension that failed to load.
     * 
     * @return the extension name (e.g., "sqlite-vec", "sqlite-graph")
     */
    public String getExtensionName() {
        return extensionName;
    }

    /**
     * Returns the platform identifier.
     * 
     * @return the platform (e.g., "linux-x86_64", "darwin-aarch64")
     */
    public String getPlatform() {
        return platform;
    }

    /**
     * Returns the path where the extension was expected.
     * 
     * @return the file path to the extension
     */
    public String getExtensionPath() {
        return extensionPath;
    }
}
