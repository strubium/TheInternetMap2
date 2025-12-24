package com.strubium.gasstation;

import com.strubium.gasstation.logger.ProjectLogger;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class GraphMLExporter {

    /**
     * Exports a JGraphT graph to GraphML in a thread-safe way.
     * It takes snapshots of vertices and edges to avoid concurrent modification.
     */
    public void exportGraph(Graph<String, DefaultEdge> graph, String fileName) throws IOException {
        ProjectLogger.LOGGER.info("Starting to export Graph");

        Set<String> verticesSnapshot = new HashSet<>();
        Set<DefaultEdge> edgesSnapshot = new HashSet<>();

        // Take snapshots safely
        synchronized (graph) {
            for (String vertex : graph.vertexSet()) {
                verticesSnapshot.add(vertex);
            }
            for (DefaultEdge edge : graph.edgeSet()) {
                edgesSnapshot.add(edge);
            }
        }

        try (FileWriter writer = new FileWriter(fileName)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
            writer.write("<graph id=\"G\" edgedefault=\"directed\">\n");

            for (String vertex : verticesSnapshot) {
                writer.write("<node id=\"" + vertex.replaceAll("[^a-zA-Z0-9_-]", "_") + "\"/>\n");
            }

            for (DefaultEdge edge : edgesSnapshot) {
                String source = graph.getEdgeSource(edge).replaceAll("[^a-zA-Z0-9_-]", "_");
                String target = graph.getEdgeTarget(edge).replaceAll("[^a-zA-Z0-9_-]", "_");
                writer.write("<edge source=\"" + source + "\" target=\"" + target + "\"/>\n");
            }

            writer.write("</graph>\n</graphml>\n");
        }

        ProjectLogger.LOGGER.info("Graph exported successfully!");
    }
}
