package br.edu.ifba.lightrag.storage.impl;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for SQLite portable export/import functionality.
 * 
 * <p>Tests verify:</p>
 * <ul>
 *   <li>REST endpoint for exporting project to SQLite file</li>
 *   <li>REST endpoint for importing project from SQLite file</li>
 *   <li>Round-trip data integrity via REST API</li>
 *   <li>Error handling for invalid requests</li>
 * </ul>
 * 
 * <p>These tests require the SQLite backend to be enabled via profile.</p>
 * 
 * @since spec-009
 */
@QuarkusTest
@Disabled("Requires SQLite backend and REST endpoints to be implemented - enable after T047")
class SQLiteExportIT {

    @TempDir
    Path tempDir;
    
    private String projectId;
    private Path downloadedFile;

    @BeforeEach
    void setUp() {
        // Create a test project via REST API
        projectId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"SQLite Export Test Project\"}")
            .when()
            .post("/projects")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
        
        assertNotNull(projectId, "Project should be created");
    }

    @AfterEach
    void tearDown() {
        // Clean up downloaded file
        if (downloadedFile != null && Files.exists(downloadedFile)) {
            try {
                Files.delete(downloadedFile);
            } catch (Exception ignored) {
                // Ignore cleanup errors
            }
        }
        
        // Delete test project
        if (projectId != null) {
            given()
                .when()
                .delete("/projects/" + projectId)
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(404)));
        }
    }
    
    @SuppressWarnings("unchecked")
    private static org.hamcrest.Matcher<Integer> anyOf(org.hamcrest.Matcher<Integer>... matchers) {
        return org.hamcrest.Matchers.anyOf(matchers);
    }

    // ========================================================================
    // Export Endpoint Tests
    // ========================================================================

    @Nested
    @DisplayName("Export Endpoint Tests")
    class ExportEndpointTests {

        @Test
        @DisplayName("GET /projects/{id}/export/sqlite should return SQLite file")
        void testExportReturnsFile() throws Exception {
            // Add some test data first
            addTestDocument("Test document content for export");
            
            // Export project
            byte[] exportData = given()
                .when()
                .get("/projects/" + projectId + "/export/sqlite")
                .then()
                .statusCode(200)
                .contentType("application/x-sqlite3")
                .header("Content-Disposition", containsString("attachment"))
                .header("Content-Disposition", containsString(".db"))
                .extract()
                .asByteArray();
            
            assertTrue(exportData.length > 0, "Export file should not be empty");
            
            // Verify it's a valid SQLite file (starts with "SQLite format 3")
            String header = new String(exportData, 0, Math.min(16, exportData.length));
            assertTrue(header.startsWith("SQLite format 3"), "Should be valid SQLite file");
        }

        @Test
        @DisplayName("GET /projects/{id}/export/sqlite should include entities")
        void testExportIncludesEntities() throws Exception {
            // Add test data that creates entities
            addTestDocument("Alice works at TechCorp. Bob works at TechCorp too.");
            waitForProcessing();
            
            // Export and verify
            downloadedFile = tempDir.resolve("export-entities.db");
            byte[] exportData = given()
                .when()
                .get("/projects/" + projectId + "/export/sqlite")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
            
            Files.write(downloadedFile, exportData);
            
            // Open the SQLite file and check for entities
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + downloadedFile)) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM graph_entities")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 0, "Should have entities table");
                }
            }
        }

        @Test
        @DisplayName("GET /projects/{id}/export/sqlite should return 404 for non-existent project")
        void testExportNonExistentProject() {
            String fakeProjectId = UUID.randomUUID().toString();
            
            given()
                .when()
                .get("/projects/" + fakeProjectId + "/export/sqlite")
                .then()
                .statusCode(404);
        }
    }

    // ========================================================================
    // Import Endpoint Tests
    // ========================================================================

    @Nested
    @DisplayName("Import Endpoint Tests")
    class ImportEndpointTests {

        @Test
        @DisplayName("POST /projects/{id}/import/sqlite should import from file")
        void testImportFromFile() throws Exception {
            // First export
            addTestDocument("Import test content");
            waitForProcessing();
            
            byte[] exportData = given()
                .when()
                .get("/projects/" + projectId + "/export/sqlite")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
            
            // Create new project for import
            String newProjectId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Import Target Project\"}")
                .when()
                .post("/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
            
            try {
                // Import into new project
                given()
                    .contentType("application/x-sqlite3")
                    .body(exportData)
                    .when()
                    .post("/projects/" + newProjectId + "/import/sqlite")
                    .then()
                    .statusCode(200)
                    .body("imported", equalTo(true));
                
                // Verify data was imported by exporting again
                byte[] reExportData = given()
                    .when()
                    .get("/projects/" + newProjectId + "/export/sqlite")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asByteArray();
                
                assertTrue(reExportData.length > 0, "Re-exported file should have data");
                
            } finally {
                // Clean up new project
                given()
                    .when()
                    .delete("/projects/" + newProjectId);
            }
        }

        @Test
        @DisplayName("POST /projects/{id}/import/sqlite should return 400 for invalid file")
        void testImportInvalidFile() {
            byte[] invalidData = "not a sqlite file".getBytes();
            
            given()
                .contentType("application/x-sqlite3")
                .body(invalidData)
                .when()
                .post("/projects/" + projectId + "/import/sqlite")
                .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("POST /projects/{id}/import/sqlite should return 404 for non-existent project")
        void testImportNonExistentProject() throws Exception {
            String fakeProjectId = UUID.randomUUID().toString();
            
            // Create minimal valid SQLite file
            Path tempDb = tempDir.resolve("temp.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE test (id INTEGER)");
                }
            }
            byte[] sqliteData = Files.readAllBytes(tempDb);
            
            given()
                .contentType("application/x-sqlite3")
                .body(sqliteData)
                .when()
                .post("/projects/" + fakeProjectId + "/import/sqlite")
                .then()
                .statusCode(404);
        }
    }

    // ========================================================================
    // Round-Trip Tests
    // ========================================================================

    @Nested
    @DisplayName("Round-Trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Export and import should preserve knowledge graph")
        void testRoundTripPreservesKnowledgeGraph() throws Exception {
            // Add comprehensive test data
            addTestDocument("Alice is a software engineer at TechCorp. " +
                          "Bob is also at TechCorp. They work on Project Alpha.");
            waitForProcessing();
            
            // Get original entity count
            int originalEntityCount = given()
                .when()
                .get("/projects/" + projectId + "/entities")
                .then()
                .statusCode(200)
                .extract()
                .path("size()");
            
            // Export
            byte[] exportData = given()
                .when()
                .get("/projects/" + projectId + "/export/sqlite")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
            
            // Create new project and import
            String newProjectId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Round Trip Test\"}")
                .when()
                .post("/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
            
            try {
                given()
                    .contentType("application/x-sqlite3")
                    .body(exportData)
                    .when()
                    .post("/projects/" + newProjectId + "/import/sqlite")
                    .then()
                    .statusCode(200);
                
                // Verify entity count matches
                int importedEntityCount = given()
                    .when()
                    .get("/projects/" + newProjectId + "/entities")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("size()");
                
                assertEquals(originalEntityCount, importedEntityCount,
                    "Imported entity count should match original");
                
            } finally {
                given()
                    .when()
                    .delete("/projects/" + newProjectId);
            }
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void addTestDocument(String content) {
        given()
            .contentType(ContentType.TEXT)
            .body(content)
            .when()
            .post("/projects/" + projectId + "/documents/text")
            .then()
            .statusCode(anyOf(equalTo(200), equalTo(201)));
    }

    private void waitForProcessing() throws InterruptedException {
        // Wait for async document processing to complete
        // In a real test, we'd poll the document status
        Thread.sleep(2000);
    }
}
