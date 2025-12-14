package br.edu.ifba.document;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BinaryFileDetector.
 * Verifies binary file detection through extension blacklist, magic bytes, and NUL byte analysis.
 */
@QuarkusTest
class BinaryFileDetectorTest {
    
    @Inject
    BinaryFileDetector detector;
    
    // Extension blacklist tests
    
    @Test
    void testRejectsCompiledPythonFile() {
        assertTrue(detector.isBinaryExtension("module.pyc"), "Should reject .pyc files");
        assertTrue(detector.isBinaryExtension("module.pyo"), "Should reject .pyo files");
    }
    
    @Test
    void testRejectsJavaCompiledFiles() {
        assertTrue(detector.isBinaryExtension("Application.class"), "Should reject .class files");
        assertTrue(detector.isBinaryExtension("lib.jar"), "Should reject .jar files");
        assertTrue(detector.isBinaryExtension("app.war"), "Should reject .war files");
    }
    
    @Test
    void testRejectsNativeLibraries() {
        assertTrue(detector.isBinaryExtension("library.so"), "Should reject .so files");
        assertTrue(detector.isBinaryExtension("library.dll"), "Should reject .dll files");
        assertTrue(detector.isBinaryExtension("library.dylib"), "Should reject .dylib files");
    }
    
    @Test
    void testRejectsExecutables() {
        assertTrue(detector.isBinaryExtension("program.exe"), "Should reject .exe files");
        assertTrue(detector.isBinaryExtension("script.bin"), "Should reject .bin files");
    }
    
    @Test
    void testAcceptsSourceCodeExtensions() {
        assertFalse(detector.isBinaryExtension("Main.java"), "Should accept .java files");
        assertFalse(detector.isBinaryExtension("script.py"), "Should accept .py files");
        assertFalse(detector.isBinaryExtension("app.js"), "Should accept .js files");
        assertFalse(detector.isBinaryExtension("component.ts"), "Should accept .ts files");
    }
    
    // Magic bytes tests
    
    @Test
    void testDetectsElfBinary() {
        byte[] elfHeader = {0x7F, 0x45, 0x4C, 0x46, 0x00, 0x00, 0x00, 0x00};
        assertTrue(detector.isBinaryContent(elfHeader), "Should detect ELF binary");
    }
    
    @Test
    void testDetectsWindowsExecutable() {
        byte[] mzHeader = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
        assertTrue(detector.isBinaryContent(mzHeader), "Should detect Windows executable (MZ)");
    }
    
    @Test
    void testDetectsJavaClassFile() {
        byte[] cafebabeHeader = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00, 0x00, 0x34};
        assertTrue(detector.isBinaryContent(cafebabeHeader), "Should detect Java .class file");
    }
    
    @Test
    void testDetectsZipArchive() {
        byte[] zipHeader = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};
        assertTrue(detector.isBinaryContent(zipHeader), "Should detect ZIP/JAR archive");
    }
    
    @Test
    void testDetectsPngImage() {
        byte[] pngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertTrue(detector.isBinaryContent(pngHeader), "Should detect PNG image");
    }
    
    // NUL byte detection tests
    
    @Test
    void testDetectsHighNulByteFrequency() {
        byte[] dataWithNuls = new byte[100];
        // Add 15 NUL bytes (above threshold of 10)
        for (int i = 0; i < 15; i++) {
            dataWithNuls[i * 6] = 0x00;
        }
        // Fill rest with text
        for (int i = 0; i < dataWithNuls.length; i++) {
            if (dataWithNuls[i] == 0) continue;
            dataWithNuls[i] = (byte) 'A';
        }
        assertTrue(detector.isBinaryContent(dataWithNuls), "Should detect binary from high NUL byte count");
    }
    
    @Test
    void testAcceptsTextContent() {
        String javaCode = "public class Hello {\n    public static void main(String[] args) {\n        System.out.println(\"Hello\");\n    }\n}";
        byte[] textContent = javaCode.getBytes();
        assertFalse(detector.isBinaryContent(textContent), "Should accept valid Java code");
    }
    
    // Combined detection tests
    
    @Test
    void testCombinedDetectionRejectsBinaryExtensionAndContent() {
        byte[] elfContent = {0x7F, 0x45, 0x4C, 0x46, 0x00, 0x00, 0x00, 0x00};
        assertTrue(detector.isBinary("program.exe", elfContent), "Should reject binary by both extension and content");
    }
    
    @Test
    void testCombinedDetectionRejectsBinaryExtensionOnly() {
        String textContent = "print('hello')";
        byte[] content = textContent.getBytes();
        assertTrue(detector.isBinary("module.pyc", content), "Should reject by extension even if content is text");
    }
    
    @Test
    void testCombinedDetectionRejectsBinaryContentOnly() {
        byte[] elfContent = {0x7F, 0x45, 0x4C, 0x46, 0x00, 0x00, 0x00, 0x00};
        assertTrue(detector.isBinary("NoExtension", elfContent), "Should reject by content even without extension");
    }
    
    @Test
    void testCombinedDetectionAcceptsValidSourceFile() {
        String pythonCode = "def hello():\n    print('Hello, World!')";
        byte[] content = pythonCode.getBytes();
        assertFalse(detector.isBinary("script.py", content), "Should accept valid Python source file");
    }
    
    @Test
    void testHandlesCaseSensitivity() {
        assertTrue(detector.isBinaryExtension("Module.PYC"), "Should handle uppercase extensions");
        assertTrue(detector.isBinaryExtension("library.SO"), "Should handle mixed case extensions");
    }
    
    @Test
    void testHandlesEmptyContent() {
        byte[] emptyContent = new byte[0];
        assertFalse(detector.isBinaryContent(emptyContent), "Should handle empty content gracefully");
    }
    
    @Test
    void testHandlesShortContent() {
        byte[] shortContent = {0x41, 0x42, 0x43}; // "ABC"
        assertFalse(detector.isBinaryContent(shortContent), "Should handle short content");
    }
}
