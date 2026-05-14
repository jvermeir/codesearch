package dev.codesearch.web;

import com.sun.net.httpserver.HttpServer;
import dev.codesearch.store.LuceneStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class WebServer {

    private final HttpServer server;
    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    public WebServer(LuceneStore store, int port, int top, int threshold, int maxPerFile, double docWeight)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        SearchHandler handler = new SearchHandler(store, top, threshold, maxPerFile, docWeight);
        server.createContext("/", handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start(int port) {
        server.start();
        logger.info("codesearch UI running at http://localhost:{}", port);
    }

    public void stop() {
        server.stop(0);
    }
}