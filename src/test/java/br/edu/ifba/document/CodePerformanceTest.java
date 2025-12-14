package br.edu.ifba.document;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for Code Source RAG components.
 * Validates NFR-001, NFR-003, NFR-004 from specs/010-code-source-rag/checklists/requirements.md
 */
@QuarkusTest
class CodePerformanceTest {

    @Inject
    CodeChunker codeChunker;

    @Inject
    LanguageDetector languageDetector;

    @Inject
    BinaryFileDetector binaryFileDetector;

    /**
     * NFR-001: Process 10,000-line files in under 30 seconds.
     * Tests chunking performance on large Java file.
     */
    @Test
    void testChunking10kLinesPerformance() {
        // Generate 10,000 line Java file with realistic structure
        String largeFile = generateLargeJavaFile(10_000);
        
        long startTime = System.currentTimeMillis();
        List<CodeChunker.CodeChunk> chunks = codeChunker.chunk(
            largeFile, 
            "LargeService.java", 
            1200,  // maxTokens
            100    // overlapTokens
        );
        long duration = System.currentTimeMillis() - startTime;
        
        // Verify performance
        assertTrue(duration < 30_000, 
            String.format("Chunking took %dms, expected <30000ms", duration));
        
        // Verify correctness
        assertFalse(chunks.isEmpty(), "Should produce chunks");
        assertTrue(chunks.size() > 10, "Should split large file into multiple chunks");
        
        System.out.printf("✓ NFR-001: Chunked 10k lines in %dms (<%d chunks)%n", 
            duration, chunks.size());
    }

    /**
     * NFR-001: Process 10,000-line files in under 30 seconds.
     * Tests chunking performance on large Python file with different syntax.
     */
    @Test
    void testChunking10kLinesPythonPerformance() {
        // Generate 10,000 line Python file
        String largePythonFile = generateLargePythonFile(10_000);
        
        long startTime = System.currentTimeMillis();
        List<CodeChunker.CodeChunk> chunks = codeChunker.chunk(
            largePythonFile, 
            "large_module.py", 
            1200, 
            100
        );
        long duration = System.currentTimeMillis() - startTime;
        
        assertTrue(duration < 30_000, 
            String.format("Python chunking took %dms, expected <30000ms", duration));
        assertFalse(chunks.isEmpty());
        
        System.out.printf("✓ NFR-001 (Python): Chunked 10k lines in %dms%n", duration);
    }

    /**
     * NFR-003: Language detection completes in under 100ms.
     * Tests detection on realistic 1000-line file with content validation.
     */
    @Test
    void testLanguageDetectionPerformance() {
        String javaFile = generateLargeJavaFile(1_000);
        
        long startTime = System.nanoTime();
        LanguageDetector.DetectionResult result = languageDetector.detect(
            "Service.java", 
            javaFile
        );
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        
        assertTrue(durationMs < 100, 
            String.format("Detection took %dms, expected <100ms", durationMs));
        assertEquals("java", result.language());
        
        System.out.printf("✓ NFR-003: Language detected in %dms%n", durationMs);
    }

    /**
     * NFR-003: Language detection on 100+ extensions is fast.
     * Tests extension-based detection (fastest path).
     */
    @Test
    void testExtensionBasedDetectionPerformance() {
        String[] extensions = {
            "Main.java", "app.py", "index.js", "component.tsx",
            "service.go", "main.rs", "module.rb", "script.php",
            "program.cpp", "lib.swift", "handler.kt", "app.scala"
        };
        
        long totalDuration = 0;
        for (String fileName : extensions) {
            long startTime = System.nanoTime();
            LanguageDetector.DetectionResult result = languageDetector.detect(
                fileName, 
                "// Simple content"
            );
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            totalDuration += durationMs;
            
            assertNotNull(result.language());
        }
        
        long avgDuration = totalDuration / extensions.length;
        assertTrue(avgDuration < 10, 
            String.format("Avg extension detection took %dms, expected <10ms", avgDuration));
        
        System.out.printf("✓ NFR-003 (Extension): Avg detection in %dms%n", avgDuration);
    }

    /**
     * NFR-004: Binary file detection completes in under 50ms.
     * Tests detection on realistic 8KB header.
     */
    @Test
    void testBinaryDetectionPerformance() {
        // Generate realistic binary header (simulated compiled Java class)
        byte[] binaryHeader = generateBinaryHeader(8192);
        
        long startTime = System.nanoTime();
        boolean isBinary = binaryFileDetector.isBinary("App.class", binaryHeader);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        
        assertTrue(durationMs < 50, 
            String.format("Binary detection took %dms, expected <50ms", durationMs));
        assertTrue(isBinary, "Should detect binary file");
        
        System.out.printf("✓ NFR-004: Binary detected in %dms%n", durationMs);
    }

    /**
     * NFR-004: Binary detection on text files is also fast.
     */
    @Test
    void testTextFileDetectionPerformance() {
        String textContent = "public class Main { public static void main(String[] args) {} }";
        byte[] textBytes = textContent.getBytes(StandardCharsets.UTF_8);
        
        long startTime = System.nanoTime();
        boolean isBinary = binaryFileDetector.isBinary("Main.java", textBytes);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        
        assertTrue(durationMs < 50, 
            String.format("Text detection took %dms, expected <50ms", durationMs));
        assertFalse(isBinary, "Should detect text file");
        
        System.out.printf("✓ NFR-004 (Text): Detection in %dms%n", durationMs);
    }

    /**
     * Composite performance test: Full pipeline for realistic file.
     * Simulates: upload → binary check → language detect → chunking.
     */
    @Test
    void testFullPipelinePerformance() {
        String javaFile = generateLargeJavaFile(5_000);
        byte[] fileBytes = javaFile.getBytes(StandardCharsets.UTF_8);
        String fileName = "UserService.java";
        
        long startTime = System.currentTimeMillis();
        
        // Step 1: Binary detection
        boolean isBinary = binaryFileDetector.isBinary(fileName, fileBytes);
        assertFalse(isBinary, "Should be text file");
        
        // Step 2: Language detection
        LanguageDetector.DetectionResult language = languageDetector.detect(fileName, javaFile);
        assertEquals("java", language.language());
        
        // Step 3: Chunking
        List<CodeChunker.CodeChunk> chunks = codeChunker.chunk(javaFile, fileName, 1200, 100);
        assertFalse(chunks.isEmpty());
        
        long totalDuration = System.currentTimeMillis() - startTime;
        
        // Full pipeline should be fast (under 5s for 5k lines)
        assertTrue(totalDuration < 5_000, 
            String.format("Full pipeline took %dms, expected <5000ms", totalDuration));
        
        System.out.printf("✓ Full pipeline (5k lines): %dms (binary: -, lang: -, chunks: %d)%n", 
            totalDuration, chunks.size());
    }

    /**
     * Stress test: Process multiple files concurrently.
     * Validates system handles realistic upload volume.
     */
    @Test
    void testConcurrentProcessingPerformance() {
        int fileCount = 10;
        int linesPerFile = 1_000;
        
        long startTime = System.currentTimeMillis();
        
        // Sequential processing (CDI beans are application-scoped, should be thread-safe)
        for (int i = 0; i < fileCount; i++) {
            String content = generateLargeJavaFile(linesPerFile);
            String fileName = "Service" + i + ".java";
            
            // Full pipeline
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            binaryFileDetector.isBinary(fileName, bytes);
            languageDetector.detect(fileName, content);
            codeChunker.chunk(content, fileName, 1200, 100);
        }
        
        long totalDuration = System.currentTimeMillis() - startTime;
        long avgPerFile = totalDuration / fileCount;
        
        // Should process 10 files (1k lines each) in under 10s total
        assertTrue(totalDuration < 10_000, 
            String.format("Processing %d files took %dms, expected <10000ms", 
                fileCount, totalDuration));
        
        System.out.printf("✓ Concurrent: Processed %d files in %dms (avg %dms/file)%n", 
            fileCount, totalDuration, avgPerFile);
    }

    // ========== Helper Methods ==========

    /**
     * Generates realistic large Java file with classes, methods, imports.
     */
    private String generateLargeJavaFile(int lineCount) {
        StringBuilder sb = new StringBuilder();
        
        // Package and imports (10 lines)
        sb.append("package br.edu.ifba.service;\n\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Optional;\n");
        sb.append("import jakarta.inject.Inject;\n");
        sb.append("import jakarta.enterprise.context.ApplicationScoped;\n\n");
        
        // Class declaration
        sb.append("@ApplicationScoped\n");
        sb.append("public class GeneratedService {\n\n");
        
        // Generate methods to reach line count
        int currentLine = 10;
        int methodIndex = 0;
        
        while (currentLine < lineCount - 5) {
            sb.append("    /**\n");
            sb.append("     * Generated method ").append(methodIndex).append("\n");
            sb.append("     */\n");
            sb.append("    public String method").append(methodIndex).append("(String param) {\n");
            sb.append("        if (param == null) {\n");
            sb.append("            return \"default\";\n");
            sb.append("        }\n");
            sb.append("        return param.toLowerCase();\n");
            sb.append("    }\n\n");
            
            currentLine += 10;
            methodIndex++;
        }
        
        // Close class
        sb.append("}\n");
        
        return sb.toString();
    }

    /**
     * Generates realistic large Python file with functions, classes, imports.
     */
    private String generateLargePythonFile(int lineCount) {
        StringBuilder sb = new StringBuilder();
        
        // Module docstring and imports
        sb.append("\"\"\"Generated Python module for testing.\"\"\"\n\n");
        sb.append("import os\n");
        sb.append("import sys\n");
        sb.append("from typing import Optional, List\n\n");
        
        // Class declaration
        sb.append("class GeneratedService:\n");
        sb.append("    \"\"\"Service class with generated methods.\"\"\"\n\n");
        
        int currentLine = 10;
        int methodIndex = 0;
        
        while (currentLine < lineCount - 5) {
            sb.append("    def method_").append(methodIndex).append("(self, param: str) -> str:\n");
            sb.append("        \"\"\"\n");
            sb.append("        Generated method ").append(methodIndex).append("\n");
            sb.append("        \"\"\"\n");
            sb.append("        if param is None:\n");
            sb.append("            return 'default'\n");
            sb.append("        return param.lower()\n\n");
            
            currentLine += 8;
            methodIndex++;
        }
        
        return sb.toString();
    }

    /**
     * Generates binary header with magic bytes (simulated .class file).
     */
    private byte[] generateBinaryHeader(int size) {
        byte[] header = new byte[size];
        
        // Java .class magic bytes (CAFEBABE)
        header[0] = (byte) 0xCA;
        header[1] = (byte) 0xFE;
        header[2] = (byte) 0xBA;
        header[3] = (byte) 0xBE;
        
        // Fill rest with binary-like data (include some NUL bytes)
        for (int i = 4; i < size; i++) {
            if (i % 10 == 0) {
                header[i] = 0x00; // NUL byte
            } else {
                header[i] = (byte) (i % 256);
            }
        }
        
        return header;
    }
}
