package com.strubium.gasstation;

import com.strubium.gasstation.logger.ProjectLogger;

import javax.xml.stream.*;
import java.io.*;
import java.util.*;
import java.util.stream.IntStream;

public class FastGraphMLExporter {

    static class QuadTree {
        final double x, y, w, h;
        double mass = 0.0;
        double cx = 0.0, cy = 0.0;
        int[] pts = new int[8];
        int count = 0;
        QuadTree[] children = null;
        int depth = 0;
        static final int MAX_DEPTH = 24;
        static final int CAPACITY = 8;
        static final double MIN_SIZE = 0.5;

        QuadTree(double x, double y, double w, double h) { this.x = x; this.y = y; this.w = w; this.h = h; }

        private void ensurePtCapacity() { if (count == pts.length) pts = Arrays.copyOf(pts, pts.length * 2); }

        void insert(int idx, double[] xs, double[] ys) {
            mass += 1.0; cx += xs[idx]; cy += ys[idx];
            if (children != null) { children[childIndex(xs[idx], ys[idx])].insert(idx, xs, ys); return; }
            ensurePtCapacity(); pts[count++] = idx;
            if (count > CAPACITY && depth < MAX_DEPTH && w > MIN_SIZE && h > MIN_SIZE) subdivide(xs, ys);
        }

        private int childIndex(double px, double py) {
            int xi = (px > x + w * 0.5) ? 1 : 0;
            int yi = (py > y + h * 0.5) ? 2 : 0;
            return xi + yi;
        }

        private void subdivide(double[] xs, double[] ys) {
            children = new QuadTree[4];
            double hw = w * 0.5, hh = h * 0.5;
            children[0] = new QuadTree(x, y, hw, hh); children[0].depth = depth + 1;
            children[1] = new QuadTree(x + hw, y, hw, hh); children[1].depth = depth + 1;
            children[2] = new QuadTree(x, y + hh, hw, hh); children[2].depth = depth + 1;
            children[3] = new QuadTree(x + hw, y + hh, hw, hh); children[3].depth = depth + 1;
            for (int i = 0; i < count; i++) {
                int p = pts[i];
                children[childIndex(xs[p], ys[p])].insert(p, xs, ys);
            }
            pts = new int[0]; count = 0;
        }

        void applyRepulsion(int idx, double[] xs, double[] ys, double theta, double repulsion, double[] force) {
            if (mass == 0.0) return;
            final double massLocal = mass, cxLocal = cx, cyLocal = cy, widthLocal = w;
            final double px = xs[idx], py = ys[idx];
            double dx = cxLocal / massLocal - px;
            double dy = cyLocal / massLocal - py;
            final double EPS = 1e-4;
            double dist2 = dx * dx + dy * dy + EPS;
            double invDist = 1.0 / Math.sqrt(dist2);
            if (children == null || (widthLocal * invDist) < theta) {
                double invDist3 = invDist * invDist * invDist;
                double scalar = repulsion * massLocal * invDist3;
                force[0] -= dx * scalar;
                force[1] -= dy * scalar;
            } else {
                for (QuadTree c : children) if (c != null && c.mass > 0.0) c.applyRepulsion(idx, xs, ys, theta, repulsion, force);
            }
        }
    }

    public static void generateGraphHtml(
            String inputGraphFile,
            String outputHtmlFile,
            int width,
            int height
    ) throws Exception {

        Set<String> nodes = new LinkedHashSet<>();
        List<String[]> edges = new ArrayList<>();

        parseGraphML(inputGraphFile, nodes, edges);
        ProjectLogger.LOGGER.info("Parsed " + nodes.size() + " nodes and " + edges.size() + " edges");

        Map<String, NodePos> positions =
                generateForceLayoutBarnesHut(nodes, edges, width, height);

        exportToHTML(outputHtmlFile, nodes, edges, positions);
        ProjectLogger.LOGGER.info("Exported to " + outputHtmlFile);
    }


    static class NodePos { double x, y; NodePos(double x, double y) { this.x = x; this.y = y; } }

    private static void parseGraphML(String fileName, Set<String> nodes, List<String[]> edges) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (FileInputStream fis = new FileInputStream(fileName)) {
            XMLStreamReader reader = factory.createXMLStreamReader(fis);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("node".equals(name)) {
                        String id = reader.getAttributeValue(null, "id");
                        if (id != null) nodes.add(id);
                    } else if ("edge".equals(name)) {
                        String source = reader.getAttributeValue(null, "source");
                        String target = reader.getAttributeValue(null, "target");
                        if (source != null && target != null) edges.add(new String[]{source, target});
                    }
                }
            }
        }
    }

    private static Map<String, NodePos> generateForceLayoutBarnesHut(Set<String> nodesSet, List<String[]> edgesList, int width, int height) {
        List<String> nodes = new ArrayList<>(nodesSet);
        int n = nodes.size();
        Map<String, Integer> idxMap = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) idxMap.put(nodes.get(i), i);

        int[] degree = new int[n];
        List<int[]> rawEdges = new ArrayList<>(edgesList.size());
        for (String[] e : edgesList) {
            Integer a = idxMap.get(e[0]);
            Integer b = idxMap.get(e[1]);
            if (a == null || b == null) continue;
            degree[a]++; degree[b]++;
            rawEdges.add(new int[]{a, b});
        }

        int[][] neighbors = new int[n][];
        for (int i = 0; i < n; i++) neighbors[i] = new int[degree[i]];
        int[] fill = new int[n];
        for (int[] e : rawEdges) {
            int a = e[0], b = e[1];
            neighbors[a][fill[a]++] = b;
            neighbors[b][fill[b]++] = a;
        }

        double[] xs = new double[n], ys = new double[n], vx = new double[n], vy = new double[n];
        Random rnd = new Random(42);
        for (int i = 0; i < n; i++) {
            xs[i] = width * 0.5 + rnd.nextDouble() * 100 - 50;
            ys[i] = height * 0.5 + rnd.nextDouble() * 100 - 50;
        }

        final double repulsion = 1000.0 * Math.sqrt(n);
        final double springLength = 80.0;
        final double springK = 0.05;
        final double damping = 0.85;
        final double theta = 0.5;
        final int iterations = 400;
        final double maxStep = 50.0;

        for (int step = 0; step < iterations; step++) {
            QuadTree qt = new QuadTree(0, 0, width, height);
            for (int i = 0; i < n; i++) qt.insert(i, xs, ys);
            IntStream.range(0, n).parallel().forEach(i -> {
                double[] force = new double[2];
                qt.applyRepulsion(i, xs, ys, theta, repulsion, force);
                for (int other : neighbors[i]) {
                    double dx = xs[other] - xs[i], dy = ys[other] - ys[i];
                    double dist = Math.sqrt(dx * dx + dy * dy) + 1e-4;
                    double f = springK * (dist - springLength);
                    force[0] += dx / dist * f;
                    force[1] += dy / dist * f;
                }
                force[0] = Math.max(-maxStep, Math.min(maxStep, force[0]));
                force[1] = Math.max(-maxStep, Math.min(maxStep, force[1]));
                vx[i] = (vx[i] + force[0]) * damping;
                vy[i] = (vy[i] + force[1]) * damping;
            });
            for (int i = 0; i < n; i++) { xs[i] += vx[i]; ys[i] += vy[i]; }
        }

        Map<String, NodePos> out = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) out.put(nodes.get(i), new NodePos(xs[i], ys[i]));
        return out;
    }

    private static void exportToHTML(String fileName, Set<String> nodesSet, List<String[]> edges, Map<String, NodePos> positions) throws IOException {
        // Compute bounding box
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (NodePos p : positions.values()) {
            if (p.x < minX) minX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.x > maxX) maxX = p.x;
            if (p.y > maxY) maxY = p.y;
        }
        double margin = 50;
        double containerWidth = maxX - minX + 2 * margin;
        double containerHeight = maxY - minY + 2 * margin;

        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            out.println("""
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<title>Graph Export</title>
<style>
html,body { height:100%; margin:0; background:#111; overflow:hidden; }
#viewport { width:100%; height:100%; position:relative; overflow:hidden; touch-action:none; }
#container { position:absolute; transform-origin:0 0; cursor:grab; }
svg { position:absolute; top:0; left:0; pointer-events:none; }
.edge { stroke:#555; stroke-width:1; vector-effect:non-scaling-stroke; transition:stroke 0.15s, stroke-width 0.15s; }
.edge.highlight { stroke:#ff4444; stroke-width:2; }
.node { position:absolute; width:10px; height:10px; background:red; border-radius:50%; transform:translate(-50%,-50%); transition:transform 0.15s, background 0.15s; }
.node:hover {
  transform: translate(-50%,-50%) scale(1.3);
  background: #ff4444;
  z-index: 1000;
}
.node:hover::after {
  content: attr(data-id);
  position: absolute;
  top: -22px; left: 15px;
  background: rgba(0,0,0,0.85);
  color: #ff4444;
  padding: 3px 6px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
  pointer-events: none;
}
</style>
</head>
<body>
<div id="viewport">
""");

            // Container that will be scaled/moved
            out.printf("<div id=\"container\" style=\"width:%.2fpx; height:%.2fpx;\">%n", containerWidth, containerHeight);
            out.printf("<svg id=\"edges\" width=\"%.2f\" height=\"%.2f\"></svg>%n", containerWidth, containerHeight);

            // Draw nodes
            for (String n : nodesSet) {
                NodePos p = positions.get(n);
                if (p == null) continue;
                double x = p.x - minX + margin;
                double y = p.y - minY + margin;
                out.printf("<div class=\"node\" data-id=\"%s\" style=\"left:%.2fpx; top:%.2fpx;\"></div>%n", n, x, y);
            }

            // Draw edges
            out.println("<script>document.addEventListener('DOMContentLoaded',()=>{");
            out.println("const container=document.getElementById('container');");
            out.println("const svg=document.getElementById('edges');");
            out.println("let scale=1, panX=0, panY=0;");
            out.println("let isPanning=false, startX=0, startY=0, startPanX=0, startPanY=0;");

            for (String[] e : edges) {
                NodePos src = positions.get(e[0]);
                NodePos tgt = positions.get(e[1]);
                if (src != null && tgt != null) {
                    double x1 = src.x - minX + margin;
                    double y1 = src.y - minY + margin;
                    double x2 = tgt.x - minX + margin;
                    double y2 = tgt.y - minY + margin;
                    out.printf("""
                {
                  const line = document.createElementNS('http://www.w3.org/2000/svg','line');
                  line.setAttribute('x1', %.2f);
                  line.setAttribute('y1', %.2f);
                  line.setAttribute('x2', %.2f);
                  line.setAttribute('y2', %.2f);
                  line.setAttribute('class', 'edge');
                  line.setAttribute('data-source', '%s');
                  line.setAttribute('data-target', '%s');
                  svg.appendChild(line);
                }
                """, x1, y1, x2, y2, e[0], e[1]);
                }
            }

            // Transform update
            out.println("""
function updateTransform(){
  container.style.transform = `translate(${panX}px, ${panY}px) scale(${scale})`;
}
updateTransform();
""");

            // Zoom
            out.println("""
document.getElementById('viewport').addEventListener('wheel',e=>{
  e.preventDefault();
  const rect=e.currentTarget.getBoundingClientRect();
  const mx=e.clientX-rect.left, my=e.clientY-rect.top;
  const factor=e.deltaY<0?1.12:1/1.12;
  const newScale=Math.max(0.05, Math.min(20, scale*factor));
  const worldX=(mx - panX)/scale;
  const worldY=(my - panY)/scale;
  scale=newScale;
  panX=mx - worldX*scale;
  panY=my - worldY*scale;
  updateTransform();
},{passive:false});
""");

            // Pan
            out.println("""
container.addEventListener('mousedown',e=>{
  if (e.button!==0) return;
  isPanning=true;
  startX=e.clientX;
  startY=e.clientY;
  startPanX=panX;
  startPanY=panY;
  container.style.cursor='grabbing';
});
window.addEventListener('mousemove',e=>{
  if(!isPanning)return;
  panX=startPanX+(e.clientX-startX);
  panY=startPanY+(e.clientY-startY);
  updateTransform();
});
window.addEventListener('mouseup',()=>{
  isPanning=false;
  container.style.cursor='grab';
});
""");

            // Highlight all edges connected to hover node (fixed)
            out.println("""
document.querySelectorAll('.node').forEach(node => {
  node.addEventListener('mouseenter', () => {
    const id = node.getAttribute('data-id');
    // iterate all lines and add highlight to connected ones
    svg.querySelectorAll('line').forEach(line => {
      if (line.getAttribute('data-source') === id || line.getAttribute('data-target') === id) {
        line.classList.add('highlight');
      }
    });
  });

  node.addEventListener('mouseleave', () => {
    // remove highlight class from any highlighted lines
    svg.querySelectorAll('line.highlight').forEach(line => {
      line.classList.remove('highlight');
    });
  });
});
""");

            // Reset on double-click
            out.println("""
document.getElementById('viewport').addEventListener('dblclick',()=>{
  scale=1; panX=0; panY=0; updateTransform();
});
""");

            out.println("});</script></div></div></body></html>");
        }
    }

}
