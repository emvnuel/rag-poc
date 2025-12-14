package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chunks source code files while respecting logical boundaries.
 * Uses regex-based heuristics for language-agnostic boundary detection.
 */
@ApplicationScoped
public class CodeChunker {
    
    // Approximate chars per token (conservative estimate)
    private static final int CHARS_PER_TOKEN = 4;
    
    /**
     * Chunk result containing text and metadata.
     */
    public record CodeChunk(
        String content,
        int startLine,
        int endLine,
        String containingScope,
        String scopeType,
        String chunkType
    ) {}
    
    /**
     * Represents a code boundary (function, class, import block).
     */
    private record Boundary(
        int lineNumber,
        String type,
        String name,
        int indentLevel
    ) {}
    
    /**
     * Chunks code content into logical units.
     *
     * @param content raw source code
     * @param fileName file name for language detection
     * @param maxTokens maximum tokens per chunk
     * @param overlapTokens overlap between chunks
     * @return list of code chunks with metadata
     */
    public List<CodeChunk> chunk(
        String content,
        String fileName,
        int maxTokens,
        int overlapTokens
    ) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        
        final int maxChars = maxTokens * CHARS_PER_TOKEN;
        final int overlapChars = overlapTokens * CHARS_PER_TOKEN;
        
        final String[] lines = content.split("\n", -1);
        final List<Boundary> boundaries = detectBoundaries(lines);
        final List<CodeChunk> chunks = new ArrayList<>();
        
        // Group lines into chunks based on boundaries
        int currentStart = 0;
        StringBuilder currentChunk = new StringBuilder();
        String currentScope = "FILE";
        String currentScopeType = "FILE";
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            
            // Check if we're at a major boundary
            final Boundary boundary = findBoundaryAt(boundaries, i + 1);
            
            // Estimate if adding this line would exceed chunk size
            final int estimatedSize = currentChunk.length() + line.length() + 1;
            
            if (estimatedSize > maxChars && currentChunk.length() > 0) {
                // Current chunk is full, emit it
                chunks.add(new CodeChunk(
                    currentChunk.toString(),
                    currentStart + 1,
                    i,
                    currentScope,
                    currentScopeType,
                    "CODE"
                ));
                
                // Start new chunk with overlap
                currentChunk = new StringBuilder();
                currentStart = Math.max(0, i - calculateOverlapLines(lines, i, overlapChars));
                
                // Add overlap lines
                for (int j = currentStart; j < i; j++) {
                    currentChunk.append(lines[j]).append("\n");
                }
            }
            
            // Add current line
            currentChunk.append(line);
            if (i < lines.length - 1) {
                currentChunk.append("\n");
            }
            
            // Update scope if we crossed a boundary
            if (boundary != null) {
                if (boundary.type.equals("CLASS") || boundary.type.equals("FUNCTION")) {
                    currentScope = boundary.name;
                    currentScopeType = boundary.type;
                }
            }
        }
        
        // Add final chunk
        if (currentChunk.length() > 0) {
            chunks.add(new CodeChunk(
                currentChunk.toString(),
                currentStart + 1,
                lines.length,
                currentScope,
                currentScopeType,
                "CODE"
            ));
        }
        
        return chunks;
    }
    
    /**
     * Detects code boundaries (functions, classes, imports).
     */
    private List<Boundary> detectBoundaries(final String[] lines) {
        final List<Boundary> boundaries = new ArrayList<>();
        
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            final int indent = countIndentation(lines[i]);
            final int lineNumber = i + 1;
            
            // Detect import statements
            if (isImportLine(line)) {
                boundaries.add(new Boundary(lineNumber, "IMPORT", "imports", indent));
                continue;
            }
            
            // Detect class declarations (universal patterns)
            final String className = detectClass(line);
            if (className != null) {
                boundaries.add(new Boundary(lineNumber, "CLASS", className, indent));
                continue;
            }
            
            // Detect function/method declarations (universal patterns)
            final String functionName = detectFunction(line);
            if (functionName != null) {
                boundaries.add(new Boundary(lineNumber, "FUNCTION", functionName, indent));
            }
        }
        
        return boundaries;
    }
    
    /**
     * Detects if line is an import statement.
     */
    private boolean isImportLine(final String line) {
        return line.startsWith("import ") ||
               line.startsWith("from ") ||
               line.startsWith("require(") ||
               line.startsWith("use ") ||
               line.startsWith("#include");
    }
    
    /**
     * Detects class declaration and returns class name.
     * Universal patterns for multiple languages.
     */
    private String detectClass(final String line) {
        // Java/C#/TypeScript: class ClassName
        // Python: class ClassName:
        // Rust: struct ClassName
        // Go: type ClassName struct
        
        final Pattern[] classPatterns = {
            Pattern.compile("^(?:export\\s+)?(?:public\\s+|private\\s+|protected\\s+)?(?:abstract\\s+)?class\\s+([A-Z][a-zA-Z0-9_]*)"),
            Pattern.compile("^(?:export\\s+)?(?:public\\s+|private\\s+)?interface\\s+([A-Z][a-zA-Z0-9_]*)"),
            Pattern.compile("^class\\s+([A-Z][a-zA-Z0-9_]*)\\s*[:\\(]"),  // Python
            Pattern.compile("^(?:pub\\s+)?struct\\s+([A-Z][a-zA-Z0-9_]*)"),  // Rust
            Pattern.compile("^(?:pub\\s+)?enum\\s+([A-Z][a-zA-Z0-9_]*)"),  // Rust
            Pattern.compile("^type\\s+([A-Z][a-zA-Z0-9_]*)\\s+struct"),  // Go
            Pattern.compile("^type\\s+([A-Z][a-zA-Z0-9_]*)\\s+interface")  // Go
        };
        
        for (final Pattern pattern : classPatterns) {
            final Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        
        return null;
    }
    
    /**
     * Detects function/method declaration and returns function name.
     * Universal patterns for multiple languages.
     */
    private String detectFunction(final String line) {
        // Java/C#: public void methodName(
        // Python: def function_name(
        // JavaScript: function functionName( or const functionName = 
        // Go: func functionName(
        // Rust: fn function_name(
        
        final Pattern[] functionPatterns = {
            Pattern.compile("^(?:export\\s+)?(?:public\\s+|private\\s+|protected\\s+|static\\s+)*(?:[A-Z][a-zA-Z0-9_<>]*\\s+)?([a-z][a-zA-Z0-9_]*)\\s*\\("),  // Java/C#
            Pattern.compile("^def\\s+([a-z_][a-zA-Z0-9_]*)\\s*\\("),  // Python
            Pattern.compile("^(?:async\\s+)?function\\s+([a-z][a-zA-Z0-9_]*)\\s*\\("),  // JavaScript
            Pattern.compile("^(?:export\\s+)?const\\s+([a-z][a-zA-Z0-9_]*)\\s*=\\s*(?:async\\s+)?\\("),  // Arrow functions
            Pattern.compile("^func\\s+([a-z][a-zA-Z0-9_]*)\\s*\\("),  // Go
            Pattern.compile("^(?:pub\\s+)?fn\\s+([a-z_][a-zA-Z0-9_]*)\\s*[<(]")  // Rust
        };
        
        for (final Pattern pattern : functionPatterns) {
            final Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        
        return null;
    }
    
    /**
     * Counts indentation level (spaces/tabs).
     */
    private int countIndentation(final String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == ' ') {
                count++;
            } else if (c == '\t') {
                count += 4;  // Treat tab as 4 spaces
            } else {
                break;
            }
        }
        return count;
    }
    
    /**
     * Finds boundary at specific line number.
     */
    private Boundary findBoundaryAt(final List<Boundary> boundaries, final int lineNumber) {
        for (final Boundary boundary : boundaries) {
            if (boundary.lineNumber == lineNumber) {
                return boundary;
            }
        }
        return null;
    }
    
    /**
     * Calculates how many lines to include in overlap.
     */
    private int calculateOverlapLines(final String[] lines, final int currentIndex, final int overlapChars) {
        int chars = 0;
        int lineCount = 0;
        
        for (int i = currentIndex - 1; i >= 0 && chars < overlapChars; i--) {
            chars += lines[i].length() + 1;  // +1 for newline
            lineCount++;
        }
        
        return lineCount;
    }
}
