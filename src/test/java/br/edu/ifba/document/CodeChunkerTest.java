package br.edu.ifba.document;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeChunker.
 * Verifies code-aware chunking with boundary detection.
 */
@QuarkusTest
class CodeChunkerTest {
    
    @Inject
    CodeChunker chunker;
    
    // T049: Test chunk at function boundary
    
    @Test
    void testChunkAtFunctionBoundary() {
        String javaCode = """
            public class Example {
                public void methodOne() {
                    System.out.println("Method 1");
                }
                
                public void methodTwo() {
                    System.out.println("Method 2");
                }
                
                public void methodThree() {
                    System.out.println("Method 3");
                }
            }
            """;
        
        // Chunk with small max to force multiple chunks
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(javaCode, "Example.java", 300, 50);
        
        assertNotNull(chunks);
        assertTrue(chunks.size() >= 2, "Should create multiple chunks for multiple methods");
        
        // Verify chunks contain complete method definitions
        boolean hasMethodOne = chunks.stream().anyMatch(c -> c.content().contains("methodOne"));
        boolean hasMethodTwo = chunks.stream().anyMatch(c -> c.content().contains("methodTwo"));
        
        assertTrue(hasMethodOne, "Should have chunk containing methodOne");
        assertTrue(hasMethodTwo, "Should have chunk containing methodTwo");
    }
    
    @Test
    void testPythonFunctionBoundary() {
        String pythonCode = """
            def function_one():
                print("Function 1")
                return 1
            
            def function_two():
                print("Function 2")
                return 2
            
            def function_three():
                print("Function 3")
                return 3
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(pythonCode, "example.py", 200, 30);
        
        assertNotNull(chunks);
        assertTrue(chunks.size() >= 2, "Should create multiple chunks");
        
        // Each chunk should contain a complete function
        for (CodeChunker.CodeChunk chunk : chunks) {
            if (chunk.content().contains("def function_")) {
                assertTrue(chunk.content().contains("return"), 
                    "Function chunk should be complete with return statement");
            }
        }
    }
    
    // T050: Test chunk at class boundary
    
    @Test
    void testChunkAtClassBoundary() {
        String javaCode = """
            public class ClassOne {
                private String name;
                
                public String getName() {
                    return name;
                }
            }
            
            public class ClassTwo {
                private int value;
                
                public int getValue() {
                    return value;
                }
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(javaCode, "Example.java", 400, 50);
        
        assertNotNull(chunks);
        
        // Classes should stay together with their methods
        for (CodeChunker.CodeChunk chunk : chunks) {
            if (chunk.content().contains("class ClassOne")) {
                assertTrue(chunk.content().contains("getName"), 
                    "ClassOne should stay with its methods");
            }
            if (chunk.content().contains("class ClassTwo")) {
                assertTrue(chunk.content().contains("getValue"), 
                    "ClassTwo should stay with its methods");
            }
        }
    }
    
    @Test
    void testTypeScriptClassBoundary() {
        String tsCode = """
            export class UserService {
                async getUser(id: number): Promise<User> {
                    return fetch(`/users/${id}`);
                }
            }
            
            export class AuthService {
                async login(username: string): Promise<Token> {
                    return fetch(`/auth/login`);
                }
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(tsCode, "services.ts", 300, 40);
        
        assertNotNull(chunks);
        
        // Each class should be in its own chunk if possible
        boolean hasUserService = chunks.stream().anyMatch(c -> 
            c.content().contains("UserService") && c.content().contains("getUser"));
        boolean hasAuthService = chunks.stream().anyMatch(c -> 
            c.content().contains("AuthService") && c.content().contains("login"));
        
        assertTrue(hasUserService, "UserService should be in a chunk with its methods");
        assertTrue(hasAuthService, "AuthService should be in a chunk with its methods");
    }
    
    // T051: Test no mid-token split
    
    @Test
    void testNoMidTokenSplit() {
        String javaCode = """
            public class Example {
                public String getLongMethodName() {
                    return "value";
                }
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(javaCode, "Example.java", 1000, 0);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "Should create at least one chunk");
        
        // Verify no chunk splits mid-identifier or mid-string
        for (CodeChunker.CodeChunk chunk : chunks) {
            String content = chunk.content();
            
            // Should not end with incomplete string literal
            long openQuotes = content.chars().filter(ch -> ch == '"').count();
            assertTrue(openQuotes % 2 == 0, "Should not split mid-string literal");
            
            // Should not end mid-statement (should end with ; or } or complete line)
            String trimmed = content.trim();
            if (!trimmed.isEmpty()) {
                char lastChar = trimmed.charAt(trimmed.length() - 1);
                assertTrue(lastChar == '}' || lastChar == ';' || lastChar == ')' || lastChar == '\n',
                    "Chunk should end at statement boundary, not mid-token");
            }
        }
    }
    
    // T052: Test large function splits at statement boundaries
    
    @Test
    void testLargeFunctionSplitsAtStatements() {
        // Create a very long function that exceeds chunk size
        StringBuilder longFunction = new StringBuilder();
        longFunction.append("public void longMethod() {\n");
        for (int i = 0; i < 50; i++) {
            longFunction.append("    System.out.println(\"Statement ").append(i).append("\");\n");
        }
        longFunction.append("}\n");
        
        String javaCode = longFunction.toString();
        
        // Force split with small chunk size
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(javaCode, "Example.java", 200, 20);
        
        assertNotNull(chunks);
        assertTrue(chunks.size() >= 2, "Large function should split into multiple chunks");
        
        // Each chunk should end at statement boundary (semicolon or brace)
        for (int i = 0; i < chunks.size() - 1; i++) {
            String content = chunks.get(i).content().trim();
            if (!content.isEmpty()) {
                char lastChar = content.charAt(content.length() - 1);
                assertTrue(lastChar == ';' || lastChar == '}' || lastChar == '{',
                    "Chunk should end at statement boundary");
            }
        }
    }
    
    // T053: Test chunk metadata
    
    @Test
    void testChunkMetadata() {
        String javaCode = """
            package com.example;
            
            public class UserService {
                public User findById(Long id) {
                    return repository.findById(id);
                }
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(javaCode, "UserService.java", 500, 50);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "Should create at least one chunk");
        
        for (CodeChunker.CodeChunk chunk : chunks) {
            // Verify metadata fields are present
            assertNotNull(chunk.content(), "Content should not be null");
            assertTrue(chunk.startLine() >= 1, "Start line should be >= 1");
            assertTrue(chunk.endLine() >= chunk.startLine(), "End line should be >= start line");
            
            // If chunk contains a method, containingScope should be set
            if (chunk.content().contains("findById")) {
                assertNotNull(chunk.containingScope(), "Should have containing scope for method");
                assertTrue(chunk.containingScope().contains("UserService") ||
                          chunk.containingScope().contains("findById"),
                    "Containing scope should reference class or method name");
            }
            
            // scopeType should be set
            assertNotNull(chunk.scopeType(), "Scope type should not be null");
            assertTrue(List.of("FILE", "CLASS", "FUNCTION", "IMPORT", "OTHER").contains(chunk.scopeType()),
                "Scope type should be one of the defined types");
            
            // chunkType should be set
            assertNotNull(chunk.chunkType(), "Chunk type should not be null");
        }
    }
    
    @Test
    void testStartAndEndLineNumbers() {
        String pythonCode = """
            def function_one():
                print("Line 2")
                print("Line 3")
            
            def function_two():
                print("Line 6")
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(pythonCode, "example.py", 300, 30);
        
        assertNotNull(chunks);
        
        for (CodeChunker.CodeChunk chunk : chunks) {
            // Line numbers should be sequential and valid
            assertTrue(chunk.startLine() >= 1, "Start line should be >= 1");
            assertTrue(chunk.endLine() >= chunk.startLine(), 
                "End line should be >= start line");
            
            // Count newlines in content - should roughly match line range
            long newlines = chunk.content().chars().filter(ch -> ch == '\n').count();
            int lineRange = chunk.endLine() - chunk.startLine() + 1;
            
            // Allow some flexibility due to chunking strategy
            assertTrue(Math.abs(newlines - lineRange) <= 3,
                "Line numbers should roughly match content line count");
        }
    }
    
    // Additional edge case tests
    
    @Test
    void testEmptyContent() {
        String emptyCode = "";
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(emptyCode, "empty.java", 1000, 100);
        
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty() || (chunks.size() == 1 && chunks.get(0).content().isEmpty()),
            "Empty content should produce empty or single empty chunk");
    }
    
    @Test
    void testSingleLineCode() {
        String singleLine = "public class Simple { }";
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(singleLine, "Simple.java", 1000, 0);
        
        assertNotNull(chunks);
        assertEquals(1, chunks.size(), "Single line should produce one chunk");
        assertTrue(chunks.get(0).content().contains("Simple"), "Should contain the code");
    }
    
    @Test
    void testImportBlockGrouping() {
        String javaCode = """
            import java.util.List;
            import java.util.ArrayList;
            import java.util.Map;
            import java.util.HashMap;
            
            public class Example {
                private List<String> items;
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(javaCode, "Example.java", 300, 30);
        
        assertNotNull(chunks);
        
        // Imports should ideally be grouped together in first chunk
        if (chunks.size() > 1) {
            CodeChunker.CodeChunk firstChunk = chunks.get(0);
            int importCount = (int) firstChunk.content().lines()
                .filter(line -> line.trim().startsWith("import"))
                .count();
            
            assertTrue(importCount >= 2, "First chunk should contain multiple imports grouped together");
        }
    }
    
    @Test
    void testGoCodeBoundaries() {
        String goCode = """
            package main
            
            import "fmt"
            
            func functionOne() {
                fmt.Println("One")
            }
            
            func functionTwo() {
                fmt.Println("Two")
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(goCode, "main.go", 250, 30);
        
        assertNotNull(chunks);
        assertTrue(chunks.size() >= 1, "Should create chunks");
        
        // Functions should be complete
        for (CodeChunker.CodeChunk chunk : chunks) {
            if (chunk.content().contains("func functionOne")) {
                assertTrue(chunk.content().contains("fmt.Println(\"One\")"),
                    "Function should be complete");
            }
        }
    }
    
    @Test
    void testRustCodeBoundaries() {
        String rustCode = """
            pub fn function_one() -> i32 {
                println!("One");
                return 1;
            }
            
            pub fn function_two() -> i32 {
                println!("Two");
                return 2;
            }
            """;
        
        List<CodeChunker.CodeChunk> chunks = chunker.chunk(rustCode, "lib.rs", 200, 20);
        
        assertNotNull(chunks);
        
        // Functions should be complete with return statements
        for (CodeChunker.CodeChunk chunk : chunks) {
            if (chunk.content().contains("function_one")) {
                assertTrue(chunk.content().contains("return 1"),
                    "Function should be complete with return");
            }
        }
    }
}
