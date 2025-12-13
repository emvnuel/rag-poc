package br.edu.ifba.lightrag.storage.impl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

/**
 * Handles SQLite schema migrations on startup.
 * 
 * <p>Migrations are stored as SQL files in the classpath at
 * {@code /db/migrations/} and are applied in version order.</p>
 * 
 * <p>Each migration file must be named with the pattern {@code V{version}__{description}.sql}
 * where version is an integer and description is a snake_case name.</p>
 * 
 * <p>Example migration files:</p>
 * <ul>
 *   <li>{@code V001__initial_schema.sql}</li>
 *   <li>{@code V002__add_indexes.sql}</li>
 * </ul>
 */
public final class SQLiteSchemaMigrator {

    private static final Logger LOG = Logger.getLogger(SQLiteSchemaMigrator.class);

    private static final String MIGRATION_PATH = "/db/migrations/";
    
    private final List<Migration> migrations;

    /**
     * Creates a new SQLiteSchemaMigrator with default migrations.
     */
    public SQLiteSchemaMigrator() {
        this.migrations = loadMigrations();
    }

    /**
     * Gets current schema version from database.
     * 
     * @param conn database connection
     * @return current version number, 0 if not initialized
     */
    public int getCurrentVersion(Connection conn) {
        try {
            // Check if schema_version table exists
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
                if (!rs.next()) {
                    return 0;
                }
            }

            // Get latest version
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) {
                    int version = rs.getInt(1);
                    if (!rs.wasNull()) {
                        return version;
                    }
                }
            }
            return 0;
        } catch (SQLException e) {
            LOG.debug("Error getting current schema version", e);
            return 0;
        }
    }

    /**
     * Applies all pending migrations.
     * 
     * @param conn database connection
     * @throws SQLException if migration fails
     */
    public void migrateToLatest(Connection conn) throws SQLException {
        int currentVersion = getCurrentVersion(conn);
        LOG.infof("Current schema version: %d", currentVersion);

        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            
            for (Migration migration : migrations) {
                if (migration.getVersion() > currentVersion) {
                    LOG.infof("Applying migration V%d: %s", 
                        migration.getVersion(), migration.getDescription());
                    migration.apply(conn);
                    LOG.infof("Migration V%d applied successfully", migration.getVersion());
                }
            }
            
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /**
     * Gets list of all available migrations.
     * 
     * @return list of migrations in version order
     */
    public List<Migration> getMigrations() {
        return new ArrayList<>(migrations);
    }

    private List<Migration> loadMigrations() {
        List<Migration> result = new ArrayList<>();
        
        // Add V001 initial schema migration
        result.add(new ResourceMigration(1, "Initial SQLite storage schema", 
            MIGRATION_PATH + "V001__initial_schema.sql"));
        
        return result;
    }

    /**
     * Migration interface.
     */
    public interface Migration {
        /**
         * Gets the version number of this migration.
         * 
         * @return version number
         */
        int getVersion();

        /**
         * Gets the description of this migration.
         * 
         * @return description
         */
        String getDescription();

        /**
         * Applies this migration to the database.
         * 
         * @param conn database connection
         * @throws SQLException if migration fails
         */
        void apply(Connection conn) throws SQLException;
    }

    /**
     * Migration that loads SQL from a classpath resource.
     */
    private static class ResourceMigration implements Migration {
        private final int version;
        private final String description;
        private final String resourcePath;

        ResourceMigration(int version, String description, String resourcePath) {
            this.version = version;
            this.description = description;
            this.resourcePath = resourcePath;
        }

        @Override
        public int getVersion() {
            return version;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            String sql = loadResource();
            executeStatements(conn, sql);
        }

        private String loadResource() {
            InputStream is = getClass().getResourceAsStream(resourcePath);
            if (is == null) {
                throw new IllegalStateException("Migration resource not found: " + resourcePath);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load migration: " + resourcePath, e);
            }
        }

        private void executeStatements(Connection conn, String sql) throws SQLException {
            // Split by semicolons, but respect quoted strings
            List<String> statements = splitStatements(sql);
            
            try (Statement stmt = conn.createStatement()) {
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                        LOG.tracef("Executing: %s", trimmed.substring(0, Math.min(50, trimmed.length())));
                        stmt.execute(trimmed);
                    }
                }
            }
        }

        private List<String> splitStatements(String sql) {
            // First, strip line comments (-- to end of line)
            String[] lines = sql.split("\n");
            StringBuilder cleanedSql = new StringBuilder();
            for (String line : lines) {
                String trimmedLine = line.trim();
                // Skip pure comment lines
                if (trimmedLine.startsWith("--")) {
                    continue;
                }
                // Remove inline comments (after code on same line)
                int commentIndex = findCommentStart(line);
                if (commentIndex >= 0) {
                    line = line.substring(0, commentIndex);
                }
                if (!line.trim().isEmpty()) {
                    cleanedSql.append(line).append("\n");
                }
            }
            
            // Now split by semicolons, respecting quoted strings
            List<String> statements = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;
            char quoteChar = 0;
            String sqlText = cleanedSql.toString();

            for (int i = 0; i < sqlText.length(); i++) {
                char c = sqlText.charAt(i);

                if (inQuote) {
                    current.append(c);
                    if (c == quoteChar) {
                        inQuote = false;
                    }
                } else if (c == '\'' || c == '"') {
                    current.append(c);
                    inQuote = true;
                    quoteChar = c;
                } else if (c == ';') {
                    String stmt = current.toString().trim();
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }

            // Add last statement if no trailing semicolon
            String last = current.toString().trim();
            if (!last.isEmpty()) {
                statements.add(last);
            }

            return statements;
        }
        
        /**
         * Finds the start of a line comment (--) that is not inside a quoted string.
         * Returns -1 if no comment found.
         */
        private int findCommentStart(String line) {
            boolean inQuote = false;
            char quoteChar = 0;
            
            for (int i = 0; i < line.length() - 1; i++) {
                char c = line.charAt(i);
                
                if (inQuote) {
                    if (c == quoteChar) {
                        inQuote = false;
                    }
                } else if (c == '\'' || c == '"') {
                    inQuote = true;
                    quoteChar = c;
                } else if (c == '-' && line.charAt(i + 1) == '-') {
                    return i;
                }
            }
            
            return -1;
        }
    }
}
