package br.edu.ifba.document;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeDocumentExtractor.
 * Verifies code file support, extraction, and metadata.
 */
@QuarkusTest
class CodeDocumentExtractorTest {
    
    @Inject
    CodeDocumentExtractor extractor;
    
    @Inject
    BinaryFileDetector binaryDetector;
    
    // T016: Test accepts code files
    
    @Test
    void testAcceptsJavaFile() {
        assertTrue(extractor.supports("Main.java"), "Should accept .java files");
    }
    
    @Test
    void testAcceptsPythonFile() {
        assertTrue(extractor.supports("script.py"), "Should accept .py files");
    }
    
    @Test
    void testAcceptsJavaScriptFile() {
        assertTrue(extractor.supports("app.js"), "Should accept .js files");
        assertTrue(extractor.supports("module.mjs"), "Should accept .mjs files");
    }
    
    @Test
    void testAcceptsTypeScriptFile() {
        assertTrue(extractor.supports("component.ts"), "Should accept .ts files");
        assertTrue(extractor.supports("Component.tsx"), "Should accept .tsx files");
    }
    
    @Test
    void testAcceptsMultipleLanguages() {
        assertTrue(extractor.supports("main.go"), "Should accept .go files");
        assertTrue(extractor.supports("main.rs"), "Should accept .rs files");
        assertTrue(extractor.supports("program.c"), "Should accept .c files");
        assertTrue(extractor.supports("app.cpp"), "Should accept .cpp files");
    }
    
    @Test
    void testRejectsNonCodeFiles() {
        assertFalse(extractor.supports("document.txt"), "Should reject .txt files");
        assertFalse(extractor.supports("README.md"), "Should reject .md files");
        assertFalse(extractor.supports("image.png"), "Should reject .png files");
    }
    
    // T017: Test rejects binary files
    
    @Test
    void testRejectsBinaryFileWithCodeExtension() throws IOException {
        // Create a binary file disguised as Python
        byte[] binaryContent = {0x7F, 0x45, 0x4C, 0x46, 0x00, 0x00, 0x00, 0x00};
        InputStream inputStream = new ByteArrayInputStream(binaryContent);
        
        Exception exception = assertThrows(IOException.class, () -> {
            extractor.extract(inputStream);
        });
        
        assertTrue(exception.getMessage().contains("binary") || 
                   exception.getMessage().contains("Binary"), 
                   "Should reject binary file with appropriate error message");
    }
    
    @Test
    void testRejectsCompiledPythonFile() {
        // Binary detector should catch this at the supports() level in practice
        assertTrue(binaryDetector.isBinaryExtension("module.pyc"), 
                   "Binary detector should identify compiled Python as binary");
    }
    
    // T015: Test basic extraction
    
    @Test
    void testExtractsSimpleJavaCode() throws IOException {
        String javaCode = "public class Hello {\n" +
                         "    public static void main(String[] args) {\n" +
                         "        System.out.println(\"Hello, World!\");\n" +
                         "    }\n" +
                         "}";
        
        InputStream inputStream = new ByteArrayInputStream(javaCode.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertEquals(javaCode, extracted, "Should preserve exact code content");
    }
    
    @Test
    void testExtractsPythonCode() throws IOException {
        String pythonCode = "def hello():\n" +
                           "    print('Hello, World!')\n" +
                           "\n" +
                           "if __name__ == '__main__':\n" +
                           "    hello()";
        
        InputStream inputStream = new ByteArrayInputStream(pythonCode.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertEquals(pythonCode, extracted, "Should preserve exact Python code");
    }
    
    @Test
    void testPreservesIndentation() throws IOException {
        String indentedCode = "class Example:\n" +
                             "    def method(self):\n" +
                             "        if True:\n" +
                             "            print('nested')";
        
        InputStream inputStream = new ByteArrayInputStream(indentedCode.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertEquals(indentedCode, extracted, "Should preserve exact indentation");
    }
    
    @Test
    void testPreservesSpecialCharacters() throws IOException {
        String specialChars = "String regex = \"\\\\w+@[\\\\w.-]+\\\\.[a-z]{2,}\";\n" +
                             "String quote = \"He said \\\"Hello\\\"\";\n" +
                             "int unicode = '\\u00A9';";
        
        InputStream inputStream = new ByteArrayInputStream(specialChars.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertEquals(specialChars, extracted, "Should preserve special characters and escape sequences");
    }
    
    // T018: Test extracts code metadata
    
    @Test
    void testExtractsJavaMetadata() throws IOException {
        String javaCode = "package com.example;\n" +
                         "\n" +
                         "import java.util.List;\n" +
                         "import java.util.ArrayList;\n" +
                         "\n" +
                         "public class UserService {\n" +
                         "    private List<String> users;\n" +
                         "    \n" +
                         "    public UserService() {\n" +
                         "        this.users = new ArrayList<>();\n" +
                         "    }\n" +
                         "}";
        
        InputStream inputStream = new ByteArrayInputStream(javaCode.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = extractor.extractMetadata(inputStream);
        
        assertNotNull(metadata);
        assertEquals("java", metadata.get("language"), "Should detect Java");
        assertEquals(12, metadata.get("lineCount"), "Should count lines correctly");
        assertTrue((Integer) metadata.get("characterCount") > 0, "Should count characters");
        
        @SuppressWarnings("unchecked")
        List<String> imports = (List<String>) metadata.get("imports");
        assertNotNull(imports, "Should extract imports");
        assertTrue(imports.contains("java.util.List"), "Should find List import");
        assertTrue(imports.contains("java.util.ArrayList"), "Should find ArrayList import");
    }
    
    @Test
    void testExtractsPythonMetadata() throws IOException {
        String pythonCode = "import os\n" +
                           "import sys\n" +
                           "from pathlib import Path\n" +
                           "\n" +
                           "def process_file(filename):\n" +
                           "    \"\"\"Process a file.\"\"\"\n" +
                           "    with open(filename) as f:\n" +
                           "        return f.read()";
        
        InputStream inputStream = new ByteArrayInputStream(pythonCode.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = extractor.extractMetadata(inputStream);
        
        assertNotNull(metadata);
        assertEquals("python", metadata.get("language"), "Should detect Python");
        assertEquals(8, metadata.get("lineCount"), "Should count lines correctly");
        
        @SuppressWarnings("unchecked")
        List<String> imports = (List<String>) metadata.get("imports");
        assertNotNull(imports, "Should extract imports");
        assertTrue(imports.contains("os"), "Should find os import");
        assertTrue(imports.contains("sys"), "Should find sys import");
        assertTrue(imports.contains("pathlib"), "Should find pathlib import");
    }
    
    @Test
    void testExtractsTypeScriptMetadata() throws IOException {
        String tsCode = "import { Component } from '@angular/core';\n" +
                       "import axios from 'axios';\n" +
                       "\n" +
                       "interface User {\n" +
                       "  name: string;\n" +
                       "  age: number;\n" +
                       "}\n" +
                       "\n" +
                       "export class UserService {\n" +
                       "  async getUser(id: number): Promise<User> {\n" +
                       "    return axios.get(`/users/${id}`);\n" +
                       "  }\n" +
                       "}";
        
        InputStream inputStream = new ByteArrayInputStream(tsCode.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = extractor.extractMetadata(inputStream);
        
        assertNotNull(metadata);
        assertEquals("typescript", metadata.get("language"), "Should detect TypeScript");
        assertEquals(13, metadata.get("lineCount"), "Should count lines correctly");
        
        @SuppressWarnings("unchecked")
        List<String> imports = (List<String>) metadata.get("imports");
        assertNotNull(imports, "Should extract imports");
        assertTrue(imports.stream().anyMatch(i -> i.contains("@angular/core")), "Should find Angular import");
        assertTrue(imports.stream().anyMatch(i -> i.contains("axios")), "Should find axios import");
    }
    
    @Test
    void testExtractsTopLevelDeclarations() throws IOException {
        String javaCode = "package com.example;\n" +
                         "\n" +
                         "public interface Repository {\n" +
                         "    void save(Object obj);\n" +
                         "}\n" +
                         "\n" +
                         "public class UserRepository implements Repository {\n" +
                         "    @Override\n" +
                         "    public void save(Object obj) {\n" +
                         "        // implementation\n" +
                         "    }\n" +
                         "}";
        
        InputStream inputStream = new ByteArrayInputStream(javaCode.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = extractor.extractMetadata(inputStream);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declarations = (List<Map<String, Object>>) metadata.get("topLevelDeclarations");
        assertNotNull(declarations, "Should extract top-level declarations");
        assertTrue(declarations.size() >= 2, "Should find at least 2 declarations (interface + class)");
    }
    
    @Test
    void testHandlesEmptyFile() throws IOException {
        String emptyCode = "";
        
        InputStream inputStream = new ByteArrayInputStream(emptyCode.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertEquals("", extracted, "Should handle empty file");
    }
    
    @Test
    void testHandlesFileWithOnlyComments() throws IOException {
        String commentsOnly = "// This is a comment\n" +
                             "/* Multi-line\n" +
                             " * comment\n" +
                             " */";
        
        InputStream inputStream = new ByteArrayInputStream(commentsOnly.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertEquals(commentsOnly, extracted, "Should preserve comments");
    }
    
    @Test
    void testDetectsLanguageFromExtensionAndContent() throws IOException {
        String goCode = "package main\n" +
                       "\n" +
                       "func main() {\n" +
                       "    println(\"Hello\")\n" +
                       "}";
        
        InputStream inputStream = new ByteArrayInputStream(goCode.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = extractor.extractMetadata(inputStream);
        
        assertEquals("go", metadata.get("language"), "Should detect Go language");
        assertEquals("extension", metadata.get("languageDetectionMethod"), "Should use extension detection method");
    }
    
    // T026: Test preserves indentation (already covered above in testPreservesIndentation)
    // T027: Test preserves special characters (already covered above in testPreservesSpecialCharacters)
    
    // T028: Test handles different encodings
    
    @Test
    void testHandlesUtf8Encoding() throws IOException {
        String utf8Code = "// UTF-8 comment with special chars: €, ñ, 中文\n" +
                         "public class Example {\n" +
                         "    String text = \"Hello 世界\";\n" +
                         "}";
        
        InputStream inputStream = new ByteArrayInputStream(utf8Code.getBytes(StandardCharsets.UTF_8));
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertEquals(utf8Code, extracted, "Should preserve UTF-8 characters");
    }
    
    @Test
    void testHandlesUtf16Encoding() throws IOException {
        String utf16Code = "// UTF-16 test\n" +
                          "public class Test {\n" +
                          "    String emoji = \"🚀\";\n" +
                          "}";
        
        // Create UTF-16 encoded bytes
        byte[] utf16Bytes = utf16Code.getBytes(StandardCharsets.UTF_16);
        InputStream inputStream = new ByteArrayInputStream(utf16Bytes);
        
        // Extract should handle UTF-16 with BOM detection
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertTrue(extracted.contains("UTF-16 test") || extracted.contains("Test"), 
                   "Should extract UTF-16 content");
    }
    
    @Test
    void testHandlesIso88591Encoding() throws IOException {
        String isoCode = "// ISO-8859-1 comment with: café, résumé\n" +
                        "public class Latin1 {\n" +
                        "    String text = \"naïve\";\n" +
                        "}";
        
        // Create ISO-8859-1 encoded bytes
        byte[] isoBytes = isoCode.getBytes(StandardCharsets.ISO_8859_1);
        InputStream inputStream = new ByteArrayInputStream(isoBytes);
        
        // Extract should handle ISO-8859-1
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertTrue(extracted.contains("Latin1"), "Should extract ISO-8859-1 content");
    }
    
    @Test
    void testDetectsUtf8WithBom() throws IOException {
        String code = "public class BomTest {}";
        
        // Create UTF-8 with BOM: EF BB BF
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + codeBytes.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(codeBytes, 0, withBom, bom.length, codeBytes.length);
        
        InputStream inputStream = new ByteArrayInputStream(withBom);
        String extracted = extractor.extract(inputStream);
        
        assertNotNull(extracted);
        assertTrue(extracted.contains("BomTest"), "Should handle UTF-8 BOM");
    }
    
    @Test
    void testHandlesEncodingInMetadata() throws IOException {
        String code = "public class EncodingTest {}";
        
        InputStream inputStream = new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = extractor.extractMetadata(inputStream);
        
        assertTrue(metadata.containsKey("encoding"), "Should include encoding in metadata");
        assertNotNull(metadata.get("encoding"), "Encoding should not be null");
    }
    
    // T043: Test unknown extension fallback
    
    @Test
    void testHandlesUnknownExtension() {
        // Files with unknown extensions should not be supported by CodeDocumentExtractor
        assertFalse(extractor.supports("README.md"), "Should not support markdown files");
        assertFalse(extractor.supports("config.ini"), "Should not support ini files");
        assertFalse(extractor.supports("data.csv"), "Should not support CSV files");
    }
    
    // T044: Test no extension
    
    @Test
    void testHandlesFileWithoutExtension() {
        // Files without extensions should not be supported by CodeDocumentExtractor
        assertFalse(extractor.supports("Makefile"), "Should not support files without extension");
        assertFalse(extractor.supports("Dockerfile"), "Should not support Dockerfile without extension");
        assertFalse(extractor.supports("README"), "Should not support files without extension");
    }
}
