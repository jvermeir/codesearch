package dev.codesearch.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.codesearch.search.FuzzySearcher;
import dev.codesearch.search.ResultMerger;
import dev.codesearch.search.SearchResult;
import dev.codesearch.store.LuceneStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchHandler implements HttpHandler {

    private final LuceneStore store;
    private final int top;
    private final int threshold;
    private final int maxPerFile;
    private final double docWeight;

    public SearchHandler(LuceneStore store, int top, int threshold, int maxPerFile, double docWeight) {
        this.store = store;
        this.top = top;
        this.threshold = threshold;
        this.maxPerFile = maxPerFile;
        this.docWeight = docWeight;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String query = parseQuery(exchange.getRequestURI());
        String html = buildPage(query);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String parseQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null) return "";
        for (String part : raw.split("&")) {
            if (part.startsWith("q=")) {
                return java.net.URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private String buildPage(String query) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>codesearch</title>
            <style>
              body { font-family: sans-serif; max-width: 960px; margin: 2rem auto; padding: 0 1rem; }
              form { display: flex; gap: .5rem; margin-bottom: 1.5rem; }
              input[type=text] { flex: 1; padding: .4rem .6rem; font-size: 1rem; border: 1px solid #ccc; border-radius: 4px; }
              button { padding: .4rem 1rem; font-size: 1rem; cursor: pointer; }
              .result { border: 1px solid #ddd; border-radius: 4px; margin-bottom: 1rem; overflow: hidden; }
              .result-header { background: #f5f5f5; padding: .4rem .7rem; font-size: .85rem; color: #555; display: flex; justify-content: space-between; }
              .result-header .path { font-weight: bold; color: #333; font-family: monospace; }
              pre { margin: 0; padding: .7rem; overflow-x: auto; font-size: .85rem; line-height: 1.5; white-space: pre-wrap; word-break: break-all; }
              mark { background: #ffe066; border-radius: 2px; padding: 0 1px; }
              .empty { color: #888; }
            </style>
            </head>
            <body>
            <h1>codesearch</h1>
            <form method="get" action="/">
              <input type="text" name="q" value="
            """);
        sb.append(escapeAttr(query));
        sb.append("""
            " autofocus placeholder="Search…">
              <button type="submit">Search</button>
            </form>
            """);

        if (!query.isBlank()) {
            List<SearchResult> candidates = FuzzySearcher.search(store, query, top * 5, threshold, docWeight);
            List<SearchResult> results = ResultMerger.merge(candidates, top, maxPerFile);

            if (results.isEmpty()) {
                sb.append("<p class=\"empty\">No results for <em>").append(escapeHtml(query)).append("</em>.</p>");
            } else {
                String[] tokens = query.trim().split("\\s+");
                for (SearchResult r : results) {
                    sb.append("<div class=\"result\">");
                    sb.append("<div class=\"result-header\">")
                      .append("<span class=\"path\">").append(escapeHtml(r.path())).append("</span>")
                      .append("<span>lines ").append(r.startLine()).append("–").append(r.endLine())
                      .append(" &nbsp; score: ").append(String.format("%.2f", r.score())).append("</span>")
                      .append("</div>");
                    sb.append("<pre>").append(highlight(escapeHtml(r.content()), tokens)).append("</pre>");
                    sb.append("</div>");
                }
            }
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private static String highlight(String escapedContent, String[] tokens) {
        String result = escapedContent;
        for (String token : tokens) {
            if (token.isBlank()) continue;
            Pattern p = Pattern.compile("(?i)" + Pattern.quote(token));
            Matcher m = p.matcher(result);
            result = m.replaceAll("<mark>$0</mark>");
        }
        return result;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escapeHtml(s).replace("\"", "&quot;");
    }
}