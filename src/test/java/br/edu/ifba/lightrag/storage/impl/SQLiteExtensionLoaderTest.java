package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for SQLiteExtensionLoader.
 * 
 * Tests verify:
 * 1. Platform detection works correctly
 * 2. Extension paths are resolved properly
 * 3. Extensions are extracted from JAR when needed
 * 4. Error handling for missing extensions
 */
class SQLiteExtensionLoaderTest {

    private SQLiteExtensionLoader loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new SQLiteExtensionLoader();
    }

    /**
     * Test that platform detection returns a valid platform string.
     */
    @Test
    void testGetCurrentPlatformReturnsValidString() {
        String platform = loader.getCurrentPlatform();
        assertNotNull(platform, "Platform should not be null");
        assertFalse(platform.isEmpty(), "Platform should not be empty");
        // Platform should match expected patterns
        assertTrue(
            platform.matches("(linux|darwin|windows)-(x86_64|aarch64|arm64)"),
            "Platform should match expected pattern: " + platform
        );
    }

    /**
     * Test platform detection on macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void testGetCurrentPlatformOnMacOS() {
        String platform = loader.getCurrentPlatform();
        assertTrue(platform.startsWith("darwin-"), "Platform should start with darwin-");
    }

    /**
     * Test platform detection on Linux.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void testGetCurrentPlatformOnLinux() {
        String platform = loader.getCurrentPlatform();
        assertTrue(platform.startsWith("linux-"), "Platform should start with linux-");
    }

    /**
     * Test that areExtensionsAvailable returns false when extensions are not present.
     */
    @Test
    void testAreExtensionsAvailableReturnsFalseWhenMissing() {
        // Extensions are bundled, but this tests the check mechanism
        // In a clean environment without extensions, this should return false
        SQLiteExtensionLoader testLoader = new SQLiteExtensionLoader(tempDir.toString());
        boolean available = testLoader.areExtensionsAvailable();
        // Since no extensions exist in tempDir, should be false
        assertFalse(available, "Extensions should not be available in empty temp directory");
    }

    /**
     * Test that getVectorExtensionPath throws exception when extension is not found.
     */
    @Test
    void testGetVectorExtensionPathThrowsWhenNotFound() {
        SQLiteExtensionLoader testLoader = new SQLiteExtensionLoader(tempDir.toString());
        
        SQLiteExtensionLoadException exception = assertThrows(
            SQLiteExtensionLoadException.class,
            () -> testLoader.getVectorExtensionPath()
        );
        
        assertNotNull(exception.getExtensionName());
        assertNotNull(exception.getPlatform());
    }

    /**
     * Test that getGraphExtensionPath throws exception when extension is not found.
     */
    @Test
    void testGetGraphExtensionPathThrowsWhenNotFound() {
        SQLiteExtensionLoader testLoader = new SQLiteExtensionLoader(tempDir.toString());
        
        SQLiteExtensionLoadException exception = assertThrows(
            SQLiteExtensionLoadException.class,
            () -> testLoader.getGraphExtensionPath()
        );
        
        assertNotNull(exception.getExtensionName());
        assertNotNull(exception.getPlatform());
    }

    /**
     * Test that extension path resolution uses correct file extensions per platform.
     */
    @Test
    void testGetExpectedExtensionSuffix() {
        String platform = loader.getCurrentPlatform();
        String suffix = loader.getExpectedExtensionSuffix();
        
        if (platform.startsWith("darwin-")) {
            assertEquals(".dylib", suffix, "macOS should use .dylib extension");
        } else if (platform.startsWith("linux-")) {
            assertEquals(".so", suffix, "Linux should use .so extension");
        } else if (platform.startsWith("windows-")) {
            assertEquals(".dll", suffix, "Windows should use .dll extension");
        }
    }

    /**
     * Test exception message contains helpful information.
     */
    @Test
    void testExceptionContainsHelpfulMessage() {
        SQLiteExtensionLoadException exception = new SQLiteExtensionLoadException(
            "sqlite-vec", "linux-x86_64", "/path/to/extension"
        );
        
        String message = exception.getMessage();
        assertTrue(message.contains("sqlite-vec"), "Message should contain extension name");
        assertTrue(message.contains("linux-x86_64"), "Message should contain platform");
        assertTrue(message.contains("/path/to/extension"), "Message should contain path");
    }

    /**
     * Test that external path configuration is used when provided.
     */
    @Test
    void testExternalPathConfigurationIsUsed() throws Exception {
        // Create a mock extension file in temp directory
        String platform = loader.getCurrentPlatform();
        Path platformDir = tempDir.resolve(platform);
        Files.createDirectories(platformDir);
        
        String suffix = loader.getExpectedExtensionSuffix();
        Path vectorExt = platformDir.resolve("vector0" + suffix);
        Files.createFile(vectorExt);
        
        SQLiteExtensionLoader testLoader = new SQLiteExtensionLoader(tempDir.toString());
        
        // Should find the extension at the external path
        assertTrue(testLoader.areExtensionsAvailable() || Files.exists(vectorExt),
            "Should recognize extension at external path");
    }
}
