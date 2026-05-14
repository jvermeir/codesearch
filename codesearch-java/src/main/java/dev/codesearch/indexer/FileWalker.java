package dev.codesearch.indexer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

public class FileWalker {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "__pycache__", "node_modules", ".venv", "venv",
        "dist", "build", ".idea", ".vscode"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".py",  ".ts",  ".tsx", ".js",  ".jsx", ".go",  ".rs",  ".java",
        ".cpp", ".c",   ".h",   ".hpp", ".cs",  ".rb",  ".php", ".swift",
        ".kt",  ".scala", ".r", ".sh",  ".bash", ".zsh", ".fish", ".sql",
        ".tf",  ".hcl", ".css", ".scss",
        ".md",  ".txt", ".rst", ".yaml", ".yml", ".toml", ".json", ".xml",
        ".html", ".ini", ".cfg", ".conf", ".env"
    );

    private static final Set<String> SKIP_EXTENSIONS = Set.of(".map", ".lock");

    // Hide the implicit public constructor
    private FileWalker() {
        throw new UnsupportedOperationException("This is a utility class");
    }

    public static List<Path> walk(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        walkStreaming(root, result::add);
        return result;
    }

    public static void walkStreaming(Path root, Consumer<Path> consumer) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (!dir.equals(root) && (SKIP_DIRS.contains(name) || name.startsWith("."))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String ext = dot >= 0 ? name.substring(dot).toLowerCase() : "";
                if (SKIP_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;
                if (TEXT_EXTENSIONS.contains(ext) || isText(file)) {
                    consumer.accept(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isText(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(512);
            for (byte b : buf) {
                if (b == 0) return false;
            }
            return buf.length > 0;
        } catch (IOException e) {
            return false;
        }
    }
}