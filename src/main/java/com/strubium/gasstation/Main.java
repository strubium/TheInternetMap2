package com.strubium.gasstation;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import static com.strubium.gasstation.FastGraphMLExporter.generateGraphHtml;

public class Main {

    public static void main(String[] args) {

        Set<String> seeds = Set.of(
                "https://itch.io/",
                "https://en.wikipedia.org/",
                "https://news.ycombinator.com/",
                "https://github.com/",
                "https://stackoverflow.com/",
                "https://opticraft.redstudio.dev/",
                "https://github.com/Desoroxxx",
                "https://www.reddit.com/",
                "https://www.medium.com/",
                "https://www.fandom.com/",
                "https://www.curseforge.com/",
                "https://modrinth.com/",
                "https://www.msn.com/en-us/",
                "https://hackclub.com/"
        );

        // Crawl config
        CrawlerManager manager = new CrawlerManager(50);

        // Virtual threads
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Phaser tracks all running tasks
        Phaser phaser = new Phaser(1); // main thread registered

        // Start crawling
        for (String seed : seeds) {
            phaser.register(); // register task
            executor.submit(new CrawlerWorker(
                    seed,
                    0,
                    manager,
                    executor,
                    phaser
            ));
        }

        // Wait for all crawlers to finish
        phaser.arriveAndAwaitAdvance();

        executor.shutdown();

        // Export results
        GraphMLExporter exporter = new GraphMLExporter();
        try {
            exporter.exportGraph(manager.getGraph(), "internet_map.graphml");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            generateGraphHtml(
                    "internet_map.graphml",
                    "graph_output.html",
                    5050,
                    5050
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        System.out.println("Crawl finished. Graph saved to internet_map.graphml");
    }
}
