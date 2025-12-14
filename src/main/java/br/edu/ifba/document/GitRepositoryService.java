package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for cloning Git repositories and extracting files for ingestion.
 * Supports filtering by file patterns and automatic cleanup of cloned repositories.
 */
@ApplicationScoped
public class GitRepositoryService {

    private static final Logger LOG = Logger.getLogger(GitRepositoryService.class);

    @Inject
    BinaryFileDetector binaryFileDetector;

    @ConfigProperty(name = "git.clone.base.dir", defaultValue = "./git-repos")
    String cloneBaseDir;

    @ConfigProperty(name = "git.clone.timeout.seconds", defaultValue = "300")
    int cloneTimeoutSeconds;

    @ConfigProperty(name = "git.max.file.size.mb", defaultValue = "10")
    int maxFileSizeMb;

    /**
     * Clones a Git repository and extracts files matching the given patterns.
     *
     * @param repoUrl The Git repository URL
     * @param branch The branch to clone (defaults to main/master if null)
     * @param patterns File patterns to match (e.g., "*.java,*.py"). If null, includes all text files.
     * @return List of extracted repository files
     * @throws GitCloneException if cloning or extraction fails
     */
    public List<RepositoryFile> cloneAndExtractFiles(String repoUrl, String branch, String patterns) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL cannot be null or empty");
        }

        UUID cloneId = UUID.randomUUID();
        Path cloneDir = Paths.get(cloneBaseDir, cloneId.toString());

        try {
            // Create base directory if it doesn't exist
            Files.createDirectories(Paths.get(cloneBaseDir));

            // Clone the repository
            LOG.infof("Cloning repository: %s (branch: %s) into %s", repoUrl, branch, cloneDir);
            cloneRepository(repoUrl, branch, cloneDir);

            // Extract files matching patterns
            List<RepositoryFile> files = extractFiles(cloneDir, patterns);
            LOG.infof("Extracted %d files from repository %s", files.size(), repoUrl);

            return files;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to clone and extract files from repository: %s", repoUrl);
            throw new GitCloneException("Failed to process repository: " + repoUrl, e);

        } finally {
            // Clean up cloned directory
            cleanupCloneDirectory(cloneDir);
        }
    }

    /**
     * Clones a Git repository using the git command-line tool.
     */
    private void cloneRepository(String repoUrl, String branch, Path targetDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("clone");
        command.add("--depth");
        command.add("1"); // Shallow clone for faster cloning

        if (branch != null && !branch.isBlank()) {
            command.add("--branch");
            command.add(branch);
        }

        command.add(repoUrl);
        command.add(targetDir.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        LOG.infof("Executing git clone command: %s", String.join(" ", command));

        Process process = pb.start();

        // Capture output for debugging
        String output = new String(process.getInputStream().readAllBytes());

        boolean completed = process.waitFor(cloneTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            throw new GitCloneException("Git clone operation timed out after " + cloneTimeoutSeconds + " seconds");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            LOG.errorf("Git clone failed with exit code %d. Output: %s", exitCode, output);
            throw new GitCloneException("Git clone failed with exit code: " + exitCode + ". Output: " + output);
        }

        LOG.infof("Successfully cloned repository to %s", targetDir);
    }

    /**
     * Extracts files from the cloned repository that match the given patterns.
     */
    private List<RepositoryFile> extractFiles(Path repoDir, String patterns) throws IOException {
        List<Pattern> filePatterns = parsePatterns(patterns);
        List<RepositoryFile> files = new ArrayList<>();
        long maxFileSizeBytes = maxFileSizeMb * 1024L * 1024L;

        Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Skip .git directory files
                if (file.toString().contains("/.git/") || file.toString().contains("\\.git\\")) {
                    return FileVisitResult.CONTINUE;
                }

                // Skip files that are too large
                if (attrs.size() > maxFileSizeBytes) {
                    LOG.debugf("Skipping file (too large): %s (%d bytes)", file, attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                String fileName = file.getFileName().toString();

                // Skip binary files
                try (var inputStream = Files.newInputStream(file)) {
                    byte[] header = inputStream.readNBytes(512);
                    if (binaryFileDetector.isBinary(fileName, header)) {
                        LOG.debugf("Skipping binary file: %s", file);
                        return FileVisitResult.CONTINUE;
                    }
                } catch (IOException e) {
                    LOG.warnf(e, "Failed to read file header for binary detection: %s", file);
                    return FileVisitResult.CONTINUE;
                }

                // Check if file matches any pattern
                boolean matches = filePatterns.isEmpty() || filePatterns.stream()
                        .anyMatch(pattern -> pattern.matcher(fileName).matches());

                if (matches) {
                    try {
                        String content = Files.readString(file);
                        String relativePath = repoDir.relativize(file).toString();

                        files.add(new RepositoryFile(
                                relativePath,
                                fileName,
                                content,
                                attrs.size()
                        ));

                        LOG.debugf("Extracted file: %s (%d bytes)", relativePath, attrs.size());

                    } catch (IOException e) {
                        LOG.warnf(e, "Failed to read file: %s", file);
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.warnf(exc, "Failed to visit file: %s", file);
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    /**
     * Parses file patterns (e.g., "*.java,*.py") into regex patterns.
     */
    private List<Pattern> parsePatterns(String patterns) {
        if (patterns == null || patterns.isBlank()) {
            return List.of(); // Empty list means match all files
        }

        return Arrays.stream(patterns.split(","))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(this::globToRegex)
                .map(Pattern::compile)
                .collect(Collectors.toList());
    }

    /**
     * Converts a glob pattern (e.g., "*.java") to a regex pattern.
     */
    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                default -> regex.append(c);
            }
        }
        regex.append("$");
        return regex.toString();
    }

    /**
     * Cleans up the cloned repository directory.
     */
    private void cleanupCloneDirectory(Path cloneDir) {
        if (!Files.exists(cloneDir)) {
            return;
        }

        try {
            LOG.debugf("Cleaning up clone directory: %s", cloneDir);

            Files.walk(cloneDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOG.warnf(e, "Failed to delete: %s", path);
                        }
                    });

            LOG.debugf("Successfully cleaned up clone directory: %s", cloneDir);

        } catch (IOException e) {
            LOG.warnf(e, "Failed to clean up clone directory: %s", cloneDir);
        }
    }

    /**
     * Represents a file extracted from a Git repository.
     */
    public record RepositoryFile(
            String relativePath,
            String fileName,
            String content,
            long size
    ) {}
}
