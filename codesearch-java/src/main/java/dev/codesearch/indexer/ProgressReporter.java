package dev.codesearch.indexer;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProgressReporter {

    private static final Logger logger = LoggerFactory.getLogger(ProgressReporter.class);

    // TODO [NEEDS HUMAN REVIEW]: This field is unused. It was likely intended to be used for logging errors but was never implemented.
    // Remove this unused "err" private field.
    // private final PrintStream err;

    private final boolean tty;
    private final long startMs = System.currentTimeMillis();

    private final AtomicInteger scanned = new AtomicInteger();
    private final AtomicInteger indexed = new AtomicInteger();
    private final AtomicInteger skippedUnchanged = new AtomicInteger();
    private final AtomicInteger skippedEmpty = new AtomicInteger();

    private volatile boolean scanDone = false;
    private volatile int total = 0;

    private Thread reporterThread;

    public ProgressReporter() {
        // this.err = System.err; // Remove this unused "err" private field.
        this.tty = System.console() != null;
    }

    public void start() {
        reporterThread = new Thread(() -> { try { reportLoop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, "progress-reporter");
        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    public void fileScanned()          { scanned.incrementAndGet(); }
    public void fileIndexed()          { indexed.incrementAndGet(); }
    public void fileSkippedUnchanged() { skippedUnchanged.incrementAndGet(); }
    public void fileSkippedEmpty()     { skippedEmpty.incrementAndGet(); }

    public void scanDone() {
        total = scanned.get();
        scanDone = true;
    }

    public void committing() {
        stopReporter();
        if (tty) logger.info("");
        logger.info("Committing...");
    }

    public void done(long totalFiles, long totalChunks) {
        // Remove this unused "err" private field.
        // logger.info(
            // "Done. Indexed {,total,number,integer}, files, skipped {skippedUnchanged,number,integer} unchanged, {skippedEmpty,number,integer} empty. Total: {totalFiles,number,integer} files · {totalChunks,number,integer}.",
            // indexed.get(), skippedUnchanged.get(), skippedEmpty.get(), totalFiles, totalChunks);
        logger.info(
            "Done. Indexed {}, files, skipped {} unchanged, {} empty. Total: {} files · {}", 
            indexed.get(), skippedUnchanged.get(), skippedEmpty.get(), totalFiles, totalChunks);
    }

    private void stopReporter() {
        if (reporterThread != null) {
            reporterThread.interrupt();
            try { reporterThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void reportLoop() throws InterruptedException {
        long sleepMs = tty ? 1_000 : 10_000;
        while (!Thread.currentThread().isInterrupted()) {
            print();
            Thread.sleep(sleepMs);
        }
        print();
    }

    private void print() {
        long elapsed = System.currentTimeMillis() - startMs;
        int ix = indexed.get();
        int sk = skippedUnchanged.get() + skippedEmpty.get();
        int done = ix + sk;

        String rate = calculateRate(done, elapsed);
        String line = scanDone
            ? buildCompletionStageLine(done, rate)
            : buildScanningStageLine(ix, sk, rate);

        if (tty) {
            logger.info("\r%-80s", line);
        } else {
            logger.info(line);
        }
    }

    private String calculateRate(int done, long elapsed) {
        return done > 0 && elapsed > 0
            ? String.format("%.0f f/s", done * 1000.0 / elapsed) : "---";
    }

    private String buildScanningStageLine(int ix, int sk, String rate) {
        return String.format("Scanning: {scanned,number,integer} found | Indexed: {ix,number,integer} | Skipped: {sk,number,integer} | {rate}",
            scanned.get(), ix, sk, rate);
    }

    private String buildCompletionStageLine(int done, String rate) {
        long elapsed = System.currentTimeMillis() - startMs;
        int pct = total > 0 ? 100 * done / total : 100;
        String eta = done > 0 && elapsed > 0 && total > done
            ? formatSeconds((long) ((elapsed / (double) done) * (total - done) / 1000)) : "0s";
        return String.format("[{done,number,integer}/{total,number,integer} | {pct,number,integer}%] {rate}  ETA {eta}",
            done, total, pct, rate, eta);
    }

    private static String formatSeconds(long secs) {
        if (secs < 60)   return secs + "s";
        if (secs < 3600) return (secs / 60) + "m" + (secs % 60) + "s";
        return (secs / 3600) + "h" + ((secs % 3600) / 60) + "m";
    }
}