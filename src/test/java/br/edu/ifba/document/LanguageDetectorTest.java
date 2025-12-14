package br.edu.ifba.document;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LanguageDetector.
 * Verifies language detection from file extensions and content validation.
 */
@QuarkusTest
class LanguageDetectorTest {
    
    @Inject
    LanguageDetector detector;
    
    // Extension detection tests (15+ languages)
    
    @Test
    void testDetectsJavaFromExtension() {
        Optional<String> language = detector.detectFromExtension("Main.java");
        assertTrue(language.isPresent(), "Should detect Java");
        assertEquals("java", language.get());
    }
    
    @Test
    void testDetectsPythonFromExtension() {
        Optional<String> language = detector.detectFromExtension("script.py");
        assertTrue(language.isPresent(), "Should detect Python");
        assertEquals("python", language.get());
    }
    
    @Test
    void testDetectsJavaScriptFromExtensions() {
        assertEquals("javascript", detector.detectFromExtension("app.js").orElse(null));
        assertEquals("javascript", detector.detectFromExtension("module.mjs").orElse(null));
    }
    
    @Test
    void testDetectsTypeScriptFromExtensions() {
        assertEquals("typescript", detector.detectFromExtension("component.ts").orElse(null));
        assertEquals("typescript", detector.detectFromExtension("Component.tsx").orElse(null));
    }
    
    @Test
    void testDetectsGoFromExtension() {
        assertEquals("go", detector.detectFromExtension("server.go").orElse(null));
    }
    
    @Test
    void testDetectsRustFromExtension() {
        assertEquals("rust", detector.detectFromExtension("main.rs").orElse(null));
    }
    
    @Test
    void testDetectsCFromExtensions() {
        assertEquals("c", detector.detectFromExtension("program.c").orElse(null));
        assertEquals("c", detector.detectFromExtension("header.h").orElse(null));
    }
    
    @Test
    void testDetectsCppFromExtensions() {
        assertEquals("cpp", detector.detectFromExtension("Application.cpp").orElse(null));
        assertEquals("cpp", detector.detectFromExtension("header.hpp").orElse(null));
        assertEquals("cpp", detector.detectFromExtension("source.cc").orElse(null));
    }
    
    @Test
    void testDetectsCSharpFromExtension() {
        assertEquals("csharp", detector.detectFromExtension("Program.cs").orElse(null));
    }
    
    @Test
    void testDetectsRubyFromExtension() {
        assertEquals("ruby", detector.detectFromExtension("server.rb").orElse(null));
    }
    
    @Test
    void testDetectsPhpFromExtension() {
        assertEquals("php", detector.detectFromExtension("index.php").orElse(null));
    }
    
    @Test
    void testDetectsSwiftFromExtension() {
        assertEquals("swift", detector.detectFromExtension("ViewController.swift").orElse(null));
    }
    
    @Test
    void testDetectsKotlinFromExtensions() {
        assertEquals("kotlin", detector.detectFromExtension("MainActivity.kt").orElse(null));
        assertEquals("kotlin", detector.detectFromExtension("build.gradle.kts").orElse(null));
    }
    
    @Test
    void testDetectsScalaFromExtension() {
        assertEquals("scala", detector.detectFromExtension("Main.scala").orElse(null));
    }
    
    @Test
    void testDetectsShellFromExtensions() {
        assertEquals("shell", detector.detectFromExtension("deploy.sh").orElse(null));
        assertEquals("shell", detector.detectFromExtension("setup.bash").orElse(null));
    }
    
    @Test
    void testDetectsSqlFromExtension() {
        assertEquals("sql", detector.detectFromExtension("schema.sql").orElse(null));
    }
    
    @Test
    void testDetectsRFromExtension() {
        assertEquals("r", detector.detectFromExtension("analysis.r").orElse(null));
    }
    
    @Test
    void testReturnsEmptyForUnknownExtension() {
        Optional<String> language = detector.detectFromExtension("README.md");
        assertFalse(language.isPresent(), "Should return empty for unsupported extension");
    }
    
    // Content validation tests
    
    @Test
    void testValidatesJavaContent() {
        String javaCode = "public class Main {\n    public static void main(String[] args) {}\n}";
        assertTrue(detector.validateContent(javaCode, "java"), "Should validate Java content");
    }
    
    @Test
    void testValidatesPythonContent() {
        String pythonCode = "def hello():\n    print('Hello')\n\nimport os";
        assertTrue(detector.validateContent(pythonCode, "python"), "Should validate Python content");
    }
    
    @Test
    void testValidatesJavaScriptContent() {
        String jsCode = "function greet() {\n    const name = 'World';\n    return `Hello, ${name}`;\n}";
        assertTrue(detector.validateContent(jsCode, "javascript"), "Should validate JavaScript content");
    }
    
    @Test
    void testValidatesTypeScriptContent() {
        String tsCode = "interface User {\n    name: string;\n}\n\nconst user: User = { name: 'John' };";
        assertTrue(detector.validateContent(tsCode, "typescript"), "Should validate TypeScript content");
    }
    
    @Test
    void testValidatesGoContent() {
        String goCode = "package main\n\nfunc main() {\n    println(\"Hello\")\n}";
        assertTrue(detector.validateContent(goCode, "go"), "Should validate Go content");
    }
    
    @Test
    void testValidatesRustContent() {
        String rustCode = "fn main() {\n    println!(\"Hello\");\n}\n\nstruct Point { x: i32, y: i32 }";
        assertTrue(detector.validateContent(rustCode, "rust"), "Should validate Rust content");
    }
    
    @Test
    void testValidatesCContent() {
        String cCode = "#include <stdio.h>\n\nint main() {\n    printf(\"Hello\\n\");\n    return 0;\n}";
        assertTrue(detector.validateContent(cCode, "c"), "Should validate C content");
    }
    
    @Test
    void testRejectsInvalidContent() {
        String htmlContent = "<html><body>Hello</body></html>";
        assertFalse(detector.validateContent(htmlContent, "java"), "Should reject invalid content for Java");
        assertFalse(detector.validateContent(htmlContent, "python"), "Should reject invalid content for Python");
    }
    
    // Combined detection tests
    
    @Test
    void testCombinedDetectionWithMatchingExtensionAndContent() {
        String javaCode = "public class Test {}";
        LanguageDetector.DetectionResult result = detector.detect("Test.java", javaCode);
        
        assertEquals("java", result.language());
        assertEquals("extension", result.method());
        assertTrue(result.confidence() >= 0.9, "Should have high confidence for matching extension and content");
    }
    
    @Test
    void testCombinedDetectionWithExtensionOnly() {
        String emptyContent = "";
        LanguageDetector.DetectionResult result = detector.detect("script.py", emptyContent);
        
        assertEquals("python", result.language());
        assertEquals("extension", result.method());
    }
    
    @Test
    void testCombinedDetectionWithUnknownExtension() {
        String pythonCode = "def test():\n    pass";
        LanguageDetector.DetectionResult result = detector.detect("NoExtension", pythonCode);
        
        // Should attempt content-based detection
        assertNotNull(result.language());
    }
    
    @Test
    void testHandlesCaseInsensitiveExtensions() {
        assertEquals("java", detector.detectFromExtension("Main.JAVA").orElse(null));
        assertEquals("python", detector.detectFromExtension("script.PY").orElse(null));
    }
    
    @Test
    void testHandlesFilesWithMultipleDots() {
        assertEquals("java", detector.detectFromExtension("my.test.file.java").orElse(null));
        assertEquals("typescript", detector.detectFromExtension("config.spec.ts").orElse(null));
    }
    
    @Test
    void testHandlesFileWithoutExtension() {
        Optional<String> language = detector.detectFromExtension("Makefile");
        assertFalse(language.isPresent(), "Should return empty for file without extension");
    }
    
    // T042: Test multiple languages (15+ required)
    
    @Test
    void testSupportsMultipleLanguages() {
        // Test 20+ languages to exceed the requirement
        String[] testFiles = {
            "Main.java",          // Java
            "script.py",          // Python
            "app.js",             // JavaScript
            "component.ts",       // TypeScript
            "server.go",          // Go
            "main.rs",            // Rust
            "program.c",          // C
            "app.cpp",            // C++
            "Program.cs",         // C#
            "server.rb",          // Ruby
            "index.php",          // PHP
            "View.swift",         // Swift
            "Main.kt",            // Kotlin
            "App.scala",          // Scala
            "deploy.sh",          // Shell
            "schema.sql",         // SQL
            "analysis.r",         // R
            "app.lua",            // Lua
            "script.pl",          // Perl
            "module.erl"          // Erlang
        };
        
        int detectedCount = 0;
        for (String fileName : testFiles) {
            Optional<String> language = detector.detectFromExtension(fileName);
            if (language.isPresent()) {
                detectedCount++;
            }
        }
        
        assertTrue(detectedCount >= 15, 
            "Should detect at least 15 languages (detected: " + detectedCount + " out of " + testFiles.length + ")");
    }
}
