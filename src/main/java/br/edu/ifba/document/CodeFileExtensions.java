package br.edu.ifba.document;

import java.util.Set;

/**
 * Centralized constants for code file extensions across 50+ programming languages.
 * Used by LanguageDetector, DocumentResources, and other components.
 */
public final class CodeFileExtensions {

    private CodeFileExtensions() {
        // Utility class - prevent instantiation
    }

    /**
     * Comprehensive set of code file extensions covering 50+ programming languages.
     * Includes compiled languages, scripting languages, markup, configuration files, and more.
     */
    public static final Set<String> CODE_EXTENSIONS = Set.of(
        // JVM Languages
        ".java", ".kt", ".kts", ".scala", ".groovy", ".clj", ".cljs",
        
        // JavaScript/TypeScript Ecosystem
        ".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".vue", ".svelte",
        
        // Python
        ".py", ".pyw", ".pyi", ".pyx",
        
        // C/C++ Family
        ".c", ".h", ".cpp", ".cc", ".cxx", ".hpp", ".hxx", ".hh",
        
        // C# / .NET
        ".cs", ".csx", ".vb", ".fs", ".fsi", ".fsx",
        
        // Systems Programming
        ".rs", ".go", ".zig",
        
        // Scripting Languages
        ".rb", ".php", ".pl", ".pm", ".lua", ".tcl", ".sh", ".bash", ".zsh", ".fish",
        
        // Functional Languages
        ".hs", ".lhs", ".ml", ".mli", ".erl", ".hrl", ".ex", ".exs", ".elm",
        
        // Mobile Development
        ".swift", ".m", ".mm", ".dart",
        
        // Web Assembly
        ".wat", ".wasm",
        
        // R / Julia / MATLAB
        ".r", ".R", ".jl",
        
        // Database
        ".sql", ".psql", ".plsql",
        
        // Configuration / Markup
        ".xml", ".json", ".yaml", ".yml", ".toml", ".ini", ".conf", ".cfg",
        
        // Build / CI/CD
        ".gradle", ".maven", ".cmake", ".make", ".dockerfile",
        
        // Shell Scripts
        ".awk", ".sed",
        
        // Lisp Family
        ".lisp", ".lsp", ".scm",
        
        // Prolog
        ".pro",
        
        // Pascal / Delphi
        ".pas", ".pp", ".dpr",
        
        // Assembly
        ".asm", ".s",
        
        // Fortran
        ".f", ".for", ".f90", ".f95",
        
        // COBOL
        ".cob", ".cbl",
        
        // Perl 6 / Raku
        ".raku", ".rakumod", ".p6",
        
        // Nim
        ".nim", ".nims",
        
        // Crystal
        ".cr",
        
        // Haxe
        ".hx",
        
        // Ada
        ".ada", ".adb", ".ads",
        
        // D
        ".d", ".di",
        
        // Verilog / VHDL (Hardware Description)
        ".v", ".vh", ".vhd", ".vhdl",
        
        // Smalltalk
        ".st",
        
        // PowerShell
        ".ps1", ".psm1", ".psd1",
        
        // Batch / CMD
        ".bat", ".cmd",
        
        // Solidity (Blockchain)
        ".sol",
        
        // Move (Blockchain)
        ".move",
        
        // Vyper (Blockchain)
        ".vy"
    );

    /**
     * Check if a filename has a code extension.
     *
     * @param fileName the file name to check
     * @return true if the file has a code extension
     */
    public static boolean isCodeFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }

        String lowerName = fileName.toLowerCase();
        return CODE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }
}
