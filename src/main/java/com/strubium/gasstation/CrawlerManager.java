package com.strubium.gasstation;

import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CrawlerManager {
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final DefaultDirectedGraph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
    private final int maxDepth;

    public CrawlerManager(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public boolean markVisited(String url) {
        String normalized = normalizeUrl(url);
        return visited.add(normalized);
    }

    public void addEdge(String from, String to) {
        String normFrom = normalizeUrl(from);
        String normTo = normalizeUrl(to);
        graph.addVertex(normFrom);
        graph.addVertex(normTo);
        graph.addEdge(normFrom, normTo);
    }

    public DefaultDirectedGraph<String, DefaultEdge> getGraph() {
        return graph;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Normalize URL so that different subdomains like www. are treated the same.
     */
    private String normalizeUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return url;

            // Strip "www." prefix
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // Rebuild a normalized URL with just scheme + host + path (no fragments, no query)
            String scheme = (uri.getScheme() != null) ? uri.getScheme() : "https";
            String path = (uri.getPath() != null) ? uri.getPath() : "";
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            return scheme + "://" + host + path;
        } catch (URISyntaxException e) {
            return url; // fallback if URL is invalid
        }
    }
}
