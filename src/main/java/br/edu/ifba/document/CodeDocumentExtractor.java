package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts text and metadata from source code files.
 * Supports multiple programming languages through extension detection.
 */
@ApplicationScoped
public class CodeDocumentExtractor implements DocumentExtractor {
    
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".java", ".py", ".js", ".jsx", ".ts", ".tsx", ".mjs",
        ".go", ".rs", ".c", ".cpp", ".cc", ".h", ".hpp",
        ".cs", ".rb", ".php", ".swift", ".kt", ".kts", ".scala",
        ".sh", ".bash", ".sql", ".r", ".lua", ".pl", ".pm"
    );
    
    @Inject
    BinaryFileDetector binaryDetector;
    
    @Inject
    LanguageDetector languageDetector;
    
    @Override
    public String extract(final InputStream inputStream) throws IOException {
        // First, read all bytes to check for binary content
        final byte[] allBytes = inputStream.readAllBytes();
        
        // Reject binary files early
        if (binaryDetector.isBinaryContent(allBytes)) {
            throw new IOException("Binary file detected - cannot extract code from binary content");
        }
        
        // Detect encoding and convert to string preserving exact formatting
        try {
            final EncodingDetectionResult encodingResult = detectEncoding(allBytes);
            return new String(encodingResult.bytesWithoutBom, encodingResult.charset);
        } catch (final Exception e) {
            throw new IOException("Failed to decode file with detected encoding: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> extractMetadata(final InputStream inputStream) throws IOException {
        // Read content for analysis
        final byte[] allBytes = inputStream.readAllBytes();
        
        // Reject binary files
        if (binaryDetector.isBinaryContent(allBytes)) {
            throw new IOException("Binary file detected - cannot extract metadata from binary content");
        }
        
        // Detect encoding and convert to string
        final EncodingDetectionResult encodingResult = detectEncoding(allBytes);
        final String content = new String(encodingResult.bytesWithoutBom, encodingResult.charset);
        
        final Map<String, Object> metadata = new HashMap<>();
        
        // Basic metrics
        metadata.put("characterCount", content.length());
        metadata.put("lineCount", content.isEmpty() ? 0 : content.split("\n", -1).length);
        metadata.put("encoding", encodingResult.charset.name());
        
        // Language detection (using extension detection method)
        metadata.put("languageDetectionMethod", "extension");
        
        // Extract language-specific metadata
        final String detectedLanguage = detectLanguageFromContent(content);
        if (detectedLanguage != null) {
            metadata.put("language", detectedLanguage);
            
            // Extract imports
            final List<String> imports = extractImports(content, detectedLanguage);
            metadata.put("imports", imports);
            
            // Extract top-level declarations
            final List<Map<String, Object>> declarations = extractTopLevelDeclarations(content, detectedLanguage);
            metadata.put("topLevelDeclarations", declarations);
        }
        
        return metadata;
    }
    
    @Override
    public boolean supports(final String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        
        // Check if file has a supported code extension
        for (final String extension : SUPPORTED_EXTENSIONS) {
            if (fileName.toLowerCase().endsWith(extension)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Detects file encoding based on BOM (Byte Order Mark) or defaults to UTF-8.
     * 
     * @param bytes The file bytes
     * @return Encoding detection result with charset and bytes without BOM
     */
    private EncodingDetectionResult detectEncoding(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new EncodingDetectionResult(StandardCharsets.UTF_8, bytes);
        }
        
        // Check for UTF-8 BOM: EF BB BF
        if (bytes.length >= 3 && 
            (bytes[0] & 0xFF) == 0xEF && 
            (bytes[1] & 0xFF) == 0xBB && 
            (bytes[2] & 0xFF) == 0xBF) {
            final byte[] withoutBom = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, withoutBom, 0, withoutBom.length);
            return new EncodingDetectionResult(StandardCharsets.UTF_8, withoutBom);
        }
        
        // Check for UTF-16 BE BOM: FE FF
        if (bytes.length >= 2 && 
            (bytes[0] & 0xFF) == 0xFE && 
            (bytes[1] & 0xFF) == 0xFF) {
            final byte[] withoutBom = new byte[bytes.length - 2];
            System.arraycopy(bytes, 2, withoutBom, 0, withoutBom.length);
            return new EncodingDetectionResult(StandardCharsets.UTF_16BE, withoutBom);
        }
        
        // Check for UTF-16 LE BOM: FF FE
        if (bytes.length >= 2 && 
            (bytes[0] & 0xFF) == 0xFF && 
            (bytes[1] & 0xFF) == 0xFE) {
            final byte[] withoutBom = new byte[bytes.length - 2];
            System.arraycopy(bytes, 2, withoutBom, 0, withoutBom.length);
            return new EncodingDetectionResult(StandardCharsets.UTF_16LE, withoutBom);
        }
        
        // Try UTF-8 validation
        try {
            final String testDecode = new String(bytes, StandardCharsets.UTF_8);
            // Check if UTF-8 decoding produces valid characters
            if (!testDecode.contains("\uFFFD") || hasHighByteRatio(bytes)) {
                return new EncodingDetectionResult(StandardCharsets.UTF_8, bytes);
            }
        } catch (final Exception e) {
            // UTF-8 decoding failed
        }
        
        // Try ISO-8859-1 as fallback (all byte sequences are valid in ISO-8859-1)
        return new EncodingDetectionResult(StandardCharsets.ISO_8859_1, bytes);
    }
    
    /**
     * Checks if byte array has high ratio of bytes > 127 (potential UTF-8 multibyte).
     * 
     * @param bytes The bytes to check
     * @return true if high byte ratio suggests UTF-8
     */
    private boolean hasHighByteRatio(final byte[] bytes) {
        int highByteCount = 0;
        for (final byte b : bytes) {
            if ((b & 0x80) != 0) {
                highByteCount++;
            }
        }
        return highByteCount > bytes.length * 0.1; // More than 10% high bytes
    }
    
    /**
     * Result of encoding detection.
     */
    private static class EncodingDetectionResult {
        final java.nio.charset.Charset charset;
        final byte[] bytesWithoutBom;
        
        EncodingDetectionResult(final java.nio.charset.Charset charset, final byte[] bytesWithoutBom) {
            this.charset = charset;
            this.bytesWithoutBom = bytesWithoutBom;
        }
    }
    
    /**
     * Detects programming language from content analysis.
     * 
     * @param content The source code content
     * @return Language name in lowercase (e.g., "java", "python")
     */
    private String detectLanguageFromContent(final String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        // Detect language based on content patterns
        if (content.contains("package ") && content.contains("import java.")) {
            return "java";
        }
        if (content.contains("def ") || content.contains("import ") && content.contains("__name__")) {
            return "python";
        }
        if (content.contains("interface ") || content.contains("export ") && content.contains(": ")) {
            return "typescript";
        }
        if (content.contains("func ") && content.contains("package ")) {
            return "go";
        }
        if (content.contains("fn ") && content.contains("use ")) {
            return "rust";
        }
        if (content.contains("function") || content.contains("const ") || content.contains("let ")) {
            return "javascript";
        }
        
        // Default fallback based on common patterns
        if (content.contains("public class") || content.contains("private class")) {
            return "java";
        }
        
        return "unknown";
    }
    
    /**
     * Extracts import statements from source code.
     * 
     * @param content The source code content
     * @param language The programming language
     * @return List of imported modules/packages
     */
    private List<String> extractImports(final String content, final String language) {
        final List<String> imports = new ArrayList<>();
        
        if (content == null || content.isEmpty()) {
            return imports;
        }
        
        switch (language) {
            case "java":
                extractJavaImports(content, imports);
                break;
            case "python":
                extractPythonImports(content, imports);
                break;
            case "javascript":
            case "typescript":
                extractJavaScriptImports(content, imports);
                break;
            case "go":
                extractGoImports(content, imports);
                break;
            case "rust":
                extractRustImports(content, imports);
                break;
            default:
                // No import extraction for unknown languages
                break;
        }
        
        return imports;
    }
    
    private void extractJavaImports(final String content, final List<String> imports) {
        final Pattern pattern = Pattern.compile("^\\s*import\\s+([a-zA-Z0-9._]+)\\s*;", Pattern.MULTILINE);
        final Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
    }
    
    private void extractPythonImports(final String content, final List<String> imports) {
        // Match "import os" or "import sys"
        final Pattern importPattern = Pattern.compile("^\\s*import\\s+([a-zA-Z0-9_]+)", Pattern.MULTILINE);
        final Matcher importMatcher = importPattern.matcher(content);
        
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }
        
        // Match "from pathlib import Path"
        final Pattern fromPattern = Pattern.compile("^\\s*from\\s+([a-zA-Z0-9_.]+)\\s+import", Pattern.MULTILINE);
        final Matcher fromMatcher = fromPattern.matcher(content);
        
        while (fromMatcher.find()) {
            imports.add(fromMatcher.group(1));
        }
    }
    
    private void extractJavaScriptImports(final String content, final List<String> imports) {
        // Match: import { Component } from '@angular/core';
        // Match: import axios from 'axios';
        // Match: import * as React from 'react';
        final Pattern pattern = Pattern.compile(
            "^\\s*import\\s+.*?from\\s+['\"]([^'\"]+)['\"]", 
            Pattern.MULTILINE
        );
        final Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
    }
    
    private void extractGoImports(final String content, final List<String> imports) {
        // Match single import: import "fmt"
        final Pattern singlePattern = Pattern.compile("^\\s*import\\s+\"([^\"]+)\"", Pattern.MULTILINE);
        final Matcher singleMatcher = singlePattern.matcher(content);
        
        while (singleMatcher.find()) {
            imports.add(singleMatcher.group(1));
        }
        
        // Match multi-import block: import ( "fmt" "os" )
        final Pattern blockPattern = Pattern.compile("import\\s*\\(([^)]+)\\)", Pattern.DOTALL);
        final Matcher blockMatcher = blockPattern.matcher(content);
        
        while (blockMatcher.find()) {
            final String block = blockMatcher.group(1);
            final Pattern importLinePattern = Pattern.compile("\"([^\"]+)\"");
            final Matcher importLineMatcher = importLinePattern.matcher(block);
            
            while (importLineMatcher.find()) {
                imports.add(importLineMatcher.group(1));
            }
        }
    }
    
    private void extractRustImports(final String content, final List<String> imports) {
        // Match: use std::io;
        // Match: use serde::{Serialize, Deserialize};
        final Pattern pattern = Pattern.compile("^\\s*use\\s+([a-zA-Z0-9_:]+)", Pattern.MULTILINE);
        final Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
    }
    
    /**
     * Extracts top-level declarations (classes, interfaces, functions).
     * 
     * @param content The source code content
     * @param language The programming language
     * @return List of declarations with name, type, and line number
     */
    private List<Map<String, Object>> extractTopLevelDeclarations(final String content, final String language) {
        final List<Map<String, Object>> declarations = new ArrayList<>();
        
        if (content == null || content.isEmpty()) {
            return declarations;
        }
        
        final String[] lines = content.split("\n", -1);
        
        switch (language) {
            case "java":
                extractJavaDeclarations(lines, declarations);
                break;
            case "python":
                extractPythonDeclarations(lines, declarations);
                break;
            case "javascript":
            case "typescript":
                extractJavaScriptDeclarations(lines, declarations);
                break;
            case "go":
                extractGoDeclarations(lines, declarations);
                break;
            case "rust":
                extractRustDeclarations(lines, declarations);
                break;
            default:
                // No declaration extraction for unknown languages
                break;
        }
        
        return declarations;
    }
    
    private void extractJavaDeclarations(final String[] lines, final List<Map<String, Object>> declarations) {
        final Pattern classPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(abstract|final)?\\s*class\\s+([a-zA-Z0-9_]+)");
        final Pattern interfacePattern = Pattern.compile("^\\s*(public|private|protected)?\\s*interface\\s+([a-zA-Z0-9_]+)");
        final Pattern enumPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*enum\\s+([a-zA-Z0-9_]+)");
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            
            Matcher matcher = classPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "class");
                decl.put("name", matcher.group(3));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = interfacePattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "interface");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = enumPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "enum");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
            }
        }
    }
    
    private void extractPythonDeclarations(final String[] lines, final List<Map<String, Object>> declarations) {
        final Pattern classPattern = Pattern.compile("^class\\s+([a-zA-Z0-9_]+)");
        final Pattern functionPattern = Pattern.compile("^def\\s+([a-zA-Z0-9_]+)\\s*\\(");
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            
            Matcher matcher = classPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "class");
                decl.put("name", matcher.group(1));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = functionPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "function");
                decl.put("name", matcher.group(1));
                decl.put("line", i + 1);
                declarations.add(decl);
            }
        }
    }
    
    private void extractJavaScriptDeclarations(final String[] lines, final List<Map<String, Object>> declarations) {
        final Pattern classPattern = Pattern.compile("^\\s*(export\\s+)?(class|interface)\\s+([a-zA-Z0-9_]+)");
        final Pattern functionPattern = Pattern.compile("^\\s*(export\\s+)?(function|async\\s+function)\\s+([a-zA-Z0-9_]+)\\s*\\(");
        final Pattern constFunctionPattern = Pattern.compile("^\\s*(export\\s+)?const\\s+([a-zA-Z0-9_]+)\\s*=\\s*(\\([^)]*\\)|[a-zA-Z0-9_]+)\\s*=>");
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            
            Matcher matcher = classPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", matcher.group(2)); // "class" or "interface"
                decl.put("name", matcher.group(3));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = functionPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "function");
                decl.put("name", matcher.group(3));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = constFunctionPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "function");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
            }
        }
    }
    
    private void extractGoDeclarations(final String[] lines, final List<Map<String, Object>> declarations) {
        final Pattern typePattern = Pattern.compile("^type\\s+([a-zA-Z0-9_]+)\\s+(struct|interface)");
        final Pattern funcPattern = Pattern.compile("^func\\s+([a-zA-Z0-9_]+)\\s*\\(");
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            
            Matcher matcher = typePattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", matcher.group(2)); // "struct" or "interface"
                decl.put("name", matcher.group(1));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = funcPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "function");
                decl.put("name", matcher.group(1));
                decl.put("line", i + 1);
                declarations.add(decl);
            }
        }
    }
    
    private void extractRustDeclarations(final String[] lines, final List<Map<String, Object>> declarations) {
        final Pattern structPattern = Pattern.compile("^(pub\\s+)?struct\\s+([a-zA-Z0-9_]+)");
        final Pattern enumPattern = Pattern.compile("^(pub\\s+)?enum\\s+([a-zA-Z0-9_]+)");
        final Pattern traitPattern = Pattern.compile("^(pub\\s+)?trait\\s+([a-zA-Z0-9_]+)");
        final Pattern fnPattern = Pattern.compile("^(pub\\s+)?fn\\s+([a-zA-Z0-9_]+)\\s*\\(");
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            
            Matcher matcher = structPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "struct");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = enumPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "enum");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = traitPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "trait");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
                continue;
            }
            
            matcher = fnPattern.matcher(line);
            if (matcher.find()) {
                final Map<String, Object> decl = new HashMap<>();
                decl.put("type", "function");
                decl.put("name", matcher.group(2));
                decl.put("line", i + 1);
                declarations.add(decl);
            }
        }
    }
}
