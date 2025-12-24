package com.strubium.gasstation;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

public class CrawlerWorker implements Runnable {

    private final String url;
    private final int depth;
    private final CrawlerManager manager;
    private final ExecutorService executor;
    private final Phaser phaser;

    public CrawlerWorker(
            String url,
            int depth,
            CrawlerManager manager,
            ExecutorService executor,
            Phaser phaser
    ) {
        this.url = url;
        this.depth = depth;
        this.manager = manager;
        this.executor = executor;
        this.phaser = phaser;
    }

    @Override
    public void run() {
        try {
            if (depth > manager.getMaxDepth() || !manager.markVisited(url)) {
                return;
            }

            System.out.println("Crawling: " + url);

            Document doc = Jsoup.connect(url)
                    .userAgent("AdvancedJavaCrawler")
                    .timeout(5000)
                    .get();

            String domainFrom = getDomain(url);
            if (domainFrom == null) return;

            Elements links = doc.select("a[href], link[href], meta[http-equiv=refresh]");

            for (Element link : links) {
                String absUrl = getAbsoluteUrl(link, url);
                if (absUrl == null) continue;

                String domainTo = getDomain(absUrl);
                if (domainTo == null) continue;

                manager.addEdge(domainFrom, domainTo);

                // Register new task
                phaser.register();

                executor.submit(
                        new CrawlerWorker(
                                absUrl,
                                depth + 1,
                                manager,
                                executor,
                                phaser
                        )
                );
            }

        } catch (Exception ignored) {
        } finally {
            // Signal task completion
            phaser.arriveAndDeregister();
        }
    }

    private String getDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String getAbsoluteUrl(Element element, String baseUrl) {
        try {
            if (element.tagName().equals("meta")) {
                String content = element.attr("content");
                int idx = content.toLowerCase().indexOf("url=");
                if (idx >= 0) {
                    return resolve(baseUrl, content.substring(idx + 4));
                }
                return null;
            }
            return resolve(baseUrl, element.attr("href"));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolve(String base, String relative) {
        try {
            return new URI(base).resolve(relative).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================
    // ENTRY POINT
    // ==========================

    public static void main(String[] args) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Phaser phaser = new Phaser(1); // main thread registered

        CrawlerManager manager = new CrawlerManager(3);

        phaser.register();
        executor.submit(new CrawlerWorker(
                "https://example.com",
                0,
                manager,
                executor,
                phaser
        ));

        // Wait for all crawling to finish
        phaser.arriveAndAwaitAdvance();

        executor.shutdown();
        System.out.println("✅ Crawl finished.");
    }
}
