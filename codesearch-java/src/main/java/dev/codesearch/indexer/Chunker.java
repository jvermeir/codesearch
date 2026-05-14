package dev.codesearch.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class Chunker {

    static final int CHUNK_LINES   = 50;
    static final int OVERLAP_LINES = 10;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
        ".py",  ".ts",  ".tsx", ".js",  ".jsx", ".go",  ".rs",  ".java",
        ".cpp", ".c",   ".h",   ".hpp", ".cs",  ".rb",  ".php", ".swift",
        ".kt",  ".scala", ".r", ".sh",  ".bash", ".zsh", ".fish", ".sql",
        ".tf",  ".hcl", ".css", ".scss"
    );

    private static final Set<String> DOC_EXTENSIONS = Set.of(".md", ".rst");

    private static final Pattern HEADING_RE = Pattern.compile("^#{1,6}\\s");

    public record Chunk(String content, int startLine, int endLine, String fileType) {}

    public static String fileType(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot).toLowerCase() : "";
        return CODE_EXTENSIONS.contains(ext) ? "code" : "doc";
    }

    public static List<Chunk> chunkFile(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        if (lines.isEmpty()) return Collections.emptyList();

        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot).toLowerCase() : "";
        String ft = fileType(path);

        if (DOC_EXTENSIONS.contains(ext)) {
            return chunkByHeadings(lines, ft);
        }
        return chunkByLines(lines, ft);
    }

    private static List<Chunk> chunkByLines(List<String> lines, String fileType) {
        List<Chunk> chunks = new ArrayList<>();
        int step  = CHUNK_LINES - OVERLAP_LINES;
        int start = 0;
        while (start < lines.size()) {
            int end     = Math.min(start + CHUNK_LINES, lines.size());
            String text = String.join("\n", lines.subList(start, end)).strip();
            if (!text.isEmpty()) {
                chunks.add(new Chunk(text, start + 1, end, fileType));
            }
            if (end >= lines.size()) break;
            start += step;
        }
        return chunks;
    }

    private static List<Chunk> chunkByHeadings(List<String> lines, String fileType) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkStart = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (HEADING_RE.matcher(lines.get(i)).find()) {
                String text = String.join("\n", lines.subList(chunkStart, i)).strip();
                if (!text.isEmpty()) {
                    chunks.add(new Chunk(text, chunkStart + 1, i, fileType));
                }
                chunkStart = i;
            }
        }
        String text = String.join("\n", lines.subList(chunkStart, lines.size())).strip();
        if (!text.isEmpty()) {
            chunks.add(new Chunk(text, chunkStart + 1, lines.size(), fileType));
        }
        return chunks;
    }
}
