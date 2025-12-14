package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects programming language from file extension and content.
 */
@ApplicationScoped
public class LanguageDetector {
    
    /**
     * Result of language detection with confidence level.
     */
    public record DetectionResult(
        String language,
        String method,  // "extension", "content", "heuristic"
        double confidence
    ) {}
    
    /**
     * Extension to language mapping (50+ languages).
     */
    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
        // JVM Languages
        Map.entry(".java", "java"),
        Map.entry(".kt", "kotlin"),
        Map.entry(".kts", "kotlin"),
        Map.entry(".scala", "scala"),
        Map.entry(".groovy", "groovy"),
        Map.entry(".clj", "clojure"),
        Map.entry(".cljs", "clojurescript"),
        
        // JavaScript/TypeScript Ecosystem
        Map.entry(".js", "javascript"),
        Map.entry(".jsx", "javascript"),
        Map.entry(".mjs", "javascript"),
        Map.entry(".cjs", "javascript"),
        Map.entry(".ts", "typescript"),
        Map.entry(".tsx", "typescript"),
        Map.entry(".vue", "vue"),
        Map.entry(".svelte", "svelte"),
        
        // Python
        Map.entry(".py", "python"),
        Map.entry(".pyw", "python"),
        Map.entry(".pyi", "python"),
        Map.entry(".pyx", "cython"),
        
        // C/C++ Family
        Map.entry(".c", "c"),
        Map.entry(".h", "c"),
        Map.entry(".cpp", "cpp"),
        Map.entry(".cc", "cpp"),
        Map.entry(".cxx", "cpp"),
        Map.entry(".hpp", "cpp"),
        Map.entry(".hxx", "cpp"),
        Map.entry(".hh", "cpp"),
        
        // C# / .NET
        Map.entry(".cs", "csharp"),
        Map.entry(".csx", "csharp"),
        Map.entry(".vb", "visualbasic"),
        Map.entry(".fs", "fsharp"),
        Map.entry(".fsx", "fsharp"),
        Map.entry(".fsi", "fsharp"),
        
        // Systems Programming
        Map.entry(".rs", "rust"),
        Map.entry(".go", "go"),
        Map.entry(".zig", "zig"),
        
        // Scripting Languages
        Map.entry(".rb", "ruby"),
        Map.entry(".php", "php"),
        Map.entry(".pl", "perl"),
        Map.entry(".pm", "perl"),
        Map.entry(".lua", "lua"),
        Map.entry(".tcl", "tcl"),
        Map.entry(".sh", "shell"),
        Map.entry(".bash", "shell"),
        Map.entry(".zsh", "shell"),
        Map.entry(".fish", "shell"),
        Map.entry(".awk", "awk"),
        Map.entry(".sed", "sed"),
        
        // Functional Languages
        Map.entry(".hs", "haskell"),
        Map.entry(".lhs", "haskell"),
        Map.entry(".ml", "ocaml"),
        Map.entry(".mli", "ocaml"),
        Map.entry(".erl", "erlang"),
        Map.entry(".hrl", "erlang"),
        Map.entry(".ex", "elixir"),
        Map.entry(".exs", "elixir"),
        Map.entry(".elm", "elm"),
        Map.entry(".lisp", "lisp"),
        Map.entry(".lsp", "lisp"),
        Map.entry(".scm", "scheme"),
        
        // Mobile Development
        Map.entry(".swift", "swift"),
        Map.entry(".m", "objectivec"),
        Map.entry(".mm", "objectivecpp"),
        Map.entry(".dart", "dart"),
        
        // Data Science / Statistics
        Map.entry(".r", "r"),
        Map.entry(".R", "r"),
        Map.entry(".jl", "julia"),
        
        // Database
        Map.entry(".sql", "sql"),
        Map.entry(".psql", "postgresql"),
        Map.entry(".plsql", "plsql"),
        
        // Configuration / Markup
        Map.entry(".xml", "xml"),
        Map.entry(".json", "json"),
        Map.entry(".yaml", "yaml"),
        Map.entry(".yml", "yaml"),
        Map.entry(".toml", "toml"),
        Map.entry(".ini", "ini"),
        Map.entry(".conf", "conf"),
        Map.entry(".cfg", "conf"),
        
        // Build Tools
        Map.entry(".gradle", "gradle"),
        Map.entry(".cmake", "cmake"),
        Map.entry(".make", "make"),
        Map.entry(".dockerfile", "dockerfile"),
        
        // Other Languages
        Map.entry(".pas", "pascal"),
        Map.entry(".pp", "pascal"),
        Map.entry(".dpr", "delphi"),
        Map.entry(".asm", "assembly"),
        Map.entry(".s", "assembly"),
        Map.entry(".f", "fortran"),
        Map.entry(".for", "fortran"),
        Map.entry(".f90", "fortran"),
        Map.entry(".f95", "fortran"),
        Map.entry(".cob", "cobol"),
        Map.entry(".cbl", "cobol"),
        Map.entry(".raku", "raku"),
        Map.entry(".p6", "perl6"),
        Map.entry(".nim", "nim"),
        Map.entry(".nims", "nim"),
        Map.entry(".cr", "crystal"),
        Map.entry(".hx", "haxe"),
        Map.entry(".ada", "ada"),
        Map.entry(".adb", "ada"),
        Map.entry(".ads", "ada"),
        Map.entry(".d", "d"),
        Map.entry(".di", "d"),
        Map.entry(".v", "verilog"),
        Map.entry(".vh", "verilog"),
        Map.entry(".vhd", "vhdl"),
        Map.entry(".vhdl", "vhdl"),
        Map.entry(".st", "smalltalk"),
        Map.entry(".ps1", "powershell"),
        Map.entry(".psm1", "powershell"),
        Map.entry(".psd1", "powershell"),
        Map.entry(".bat", "batch"),
        Map.entry(".cmd", "batch"),
        Map.entry(".sol", "solidity"),
        Map.entry(".move", "move"),
        Map.entry(".vy", "vyper"),
        Map.entry(".wat", "webassembly"),
        Map.entry(".wasm", "webassembly"),
        Map.entry(".pro", "prolog")
    );
    
    /**
     * Content validation patterns for 30+ common languages.
     */
    private static final Map<String, Pattern> VALIDATION_PATTERNS = Map.ofEntries(
        // JVM Languages
        Map.entry("java", Pattern.compile("\\b(class|interface|package|public|private|import)\\s+")),
        Map.entry("kotlin", Pattern.compile("\\b(fun|class|val|var|object|interface|data class)\\s+")),
        Map.entry("scala", Pattern.compile("\\b(def|object|class|trait|val|var|import)\\s+")),
        Map.entry("groovy", Pattern.compile("\\b(def|class|package|import|println)\\s+")),
        Map.entry("clojure", Pattern.compile("\\(\\s*(defn|def|let|fn|ns|require)\\s+")),
        
        // JavaScript/TypeScript
        Map.entry("javascript", Pattern.compile("\\b(function|const|let|var|class|import|export|=>)\\s*")),
        Map.entry("typescript", Pattern.compile("\\b(interface|type|const|let|function|class|import|export)\\s*:\\s*\\w+")),
        Map.entry("vue", Pattern.compile("(<template>|<script>|export\\s+default|Vue\\.component)")),
        
        // Python
        Map.entry("python", Pattern.compile("\\b(def|import|from|class|if|elif|else|for|while)\\s+")),
        
        // C/C++
        Map.entry("c", Pattern.compile("\\b(#include|void|int|char|float|double|struct|return)\\s+")),
        Map.entry("cpp", Pattern.compile("\\b(class|namespace|template|using|std::|public|private|protected)\\s*")),
        
        // C# / .NET
        Map.entry("csharp", Pattern.compile("\\b(namespace|class|public|private|using|static|void|int|string)\\s+")),
        Map.entry("fsharp", Pattern.compile("\\b(let|module|type|match|with|function|open)\\s+")),
        
        // Systems Languages
        Map.entry("go", Pattern.compile("\\b(package|func|import|type|struct|interface)\\s+")),
        Map.entry("rust", Pattern.compile("\\b(fn|impl|struct|enum|trait|pub|use|let|mut)\\s+")),
        Map.entry("zig", Pattern.compile("\\b(pub|fn|const|var|struct|enum|test)\\s+")),
        
        // Scripting Languages
        Map.entry("ruby", Pattern.compile("\\b(def|class|module|require|end|do|if|elsif|else)\\s+")),
        Map.entry("php", Pattern.compile("(<\\?php|function|class|namespace|use|public|private|protected)\\s*")),
        Map.entry("perl", Pattern.compile("\\b(sub|package|use|my|our|if|elsif|else|foreach)\\s+")),
        Map.entry("lua", Pattern.compile("\\b(function|local|if|then|end|for|while|do)\\s+")),
        Map.entry("shell", Pattern.compile("(#!/bin/|\\bfunction\\s+\\w+|\\bif\\s+\\[|\\bfor\\s+\\w+\\s+in)")),
        
        // Functional Languages
        Map.entry("haskell", Pattern.compile("\\b(module|import|where|let|in|data|type|class|instance)\\s+")),
        Map.entry("ocaml", Pattern.compile("\\b(let|module|type|match|with|fun|open)\\s+")),
        Map.entry("erlang", Pattern.compile("\\b(-module|-export|-import|fun|case|of|when)\\s*")),
        Map.entry("elixir", Pattern.compile("\\b(def|defmodule|defp|import|alias|use|case|cond)\\s+")),
        
        // Mobile
        Map.entry("swift", Pattern.compile("\\b(func|class|struct|enum|protocol|var|let|import)\\s+")),
        Map.entry("dart", Pattern.compile("\\b(void|class|import|var|final|const|if|else)\\s+")),
        
        // Data Science
        Map.entry("r", Pattern.compile("\\b(function|if|else|for|while|library|require|<-)\\s*")),
        Map.entry("julia", Pattern.compile("\\b(function|end|if|elseif|else|for|while|using|import)\\s+")),
        
        // Database
        Map.entry("sql", Pattern.compile("\\b(SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|FROM|WHERE|JOIN)\\b", Pattern.CASE_INSENSITIVE)),
        
        // Configuration
        Map.entry("yaml", Pattern.compile("^\\s*[\\w-]+:\\s*")),
        Map.entry("json", Pattern.compile("^\\s*[{\\[]|\"\\w+\"\\s*:")),
        
        // Other Languages
        Map.entry("fortran", Pattern.compile("\\b(PROGRAM|SUBROUTINE|FUNCTION|INTEGER|REAL|DO|IF|ENDIF)\\b", Pattern.CASE_INSENSITIVE)),
        Map.entry("cobol", Pattern.compile("\\b(IDENTIFICATION|DIVISION|PROCEDURE|PERFORM|MOVE|TO|IF|ELSE)\\b", Pattern.CASE_INSENSITIVE)),
        Map.entry("pascal", Pattern.compile("\\b(program|procedure|function|var|begin|end|if|then|else)\\b", Pattern.CASE_INSENSITIVE)),
        Map.entry("nim", Pattern.compile("\\b(proc|func|var|let|const|type|import|when|if|elif|else)\\s+")),
        Map.entry("crystal", Pattern.compile("\\b(def|class|module|struct|require|if|elsif|else|end)\\s+")),
        Map.entry("powershell", Pattern.compile("\\b(function|param|if|else|foreach|while|\\$\\w+)\\s*")),
        Map.entry("solidity", Pattern.compile("\\b(contract|function|modifier|event|require|public|private|internal)\\s+"))
    );
    
    /**
     * Detect language from file name and optionally content.
     *
     * @param fileName the file name
     * @param content the file content (optional)
     * @return detection result with language and confidence
     */
    public DetectionResult detect(String fileName, String content) {
        // Try extension-based detection first
        Optional<String> extensionLang = detectFromExtension(fileName);
        
        if (extensionLang.isPresent()) {
            String language = extensionLang.get();
            
            // Validate with content if available
            if (content != null && !content.isEmpty()) {
                boolean valid = validateContent(content, language);
                double confidence = valid ? 0.95 : 0.75; // High confidence if content validates
                return new DetectionResult(language, "extension", confidence);
            }
            
            // Extension only, good confidence
            return new DetectionResult(language, "extension", 0.85);
        }
        
        // No extension match - try content-based detection
        if (content != null && !content.isEmpty()) {
            Optional<String> contentLang = detectFromContent(content);
            if (contentLang.isPresent()) {
                return new DetectionResult(contentLang.get(), "content", 0.65);
            }
        }
        
        // Unknown language
        return new DetectionResult("unknown", "heuristic", 0.0);
    }
    
    /**
     * Get language from extension only.
     *
     * @param fileName the file name
     * @return detected language if extension is recognized
     */
    public Optional<String> detectFromExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return Optional.empty();
        }
        
        String lowerFileName = fileName.toLowerCase();
        
        // Find the last dot to get extension
        int lastDot = lowerFileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == lowerFileName.length() - 1) {
            return Optional.empty();
        }
        
        String extension = lowerFileName.substring(lastDot);
        return Optional.ofNullable(EXTENSION_MAP.get(extension));
    }
    
    /**
     * Detect language from content patterns.
     *
     * @param content the file content
     * @return detected language if patterns match
     */
    private Optional<String> detectFromContent(String content) {
        // Try each language pattern
        for (Map.Entry<String, Pattern> entry : VALIDATION_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(content).find()) {
                return Optional.of(entry.getKey());
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Validate content matches expected language.
     *
     * @param content the file content
     * @param expectedLanguage the expected language
     * @return true if content matches the expected language
     */
    public boolean validateContent(String content, String expectedLanguage) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        
        Pattern pattern = VALIDATION_PATTERNS.get(expectedLanguage);
        if (pattern == null) {
            // No validation pattern for this language - accept it
            return true;
        }
        
        return pattern.matcher(content).find();
    }
}
