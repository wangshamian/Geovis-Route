package org.heigit.geovisroute.routing.graphhopper.extensions;

import com.graphhopper.storage.BaseGraph;
import org.heigit.geovisroute.config.profile.CustomRoadNetworkProperties;
import org.heigit.geovisroute.routing.graphhopper.extensions.CustomRoadNetworkImporter.ImportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时路网更新编排器。
 * <p>
 * 在持有 {@link org.heigit.geovisroute.routing.GraphUpdateLock} 写锁的前提下：
 * 解冻图 → 导入 GeoJSON（add / overlay）→ 重建位置索引 → 重建 CH → 落盘，
 * 使新路网立即对后续路由请求生效。
 */
public class RuntimeRoadNetworkUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeRoadNetworkUpdater.class);

    private final GRGraphHopper graphHopper;

    public RuntimeRoadNetworkUpdater(GRGraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    public UpdateResult apply(String geoJson, CustomRoadNetworkProperties config) {
        String blockReason = graphHopper.getRuntimeUpdateBlockReason();
        if (blockReason != null) {
            throw new UnsupportedOperationException(
                    "Runtime road network update is not supported for profile "
                            + graphHopper.getProfileName() + ": " + blockReason);
        }

        BaseGraph graph = graphHopper.getBaseGraph();
        int nodesBefore = graph.getNodes();
        int edgesBefore = graph.getEdges();
        boolean wasFrozen = graph.isFrozen();
        long totalStart = System.currentTimeMillis();

        try {
            if (wasFrozen) {
                graph.unfreeze();
            }

            long importStart = System.currentTimeMillis();
            graphHopper.initOSMParsersIfNeeded();
            ImportSummary summary = new CustomRoadNetworkImporter(
                    graph, graphHopper.getEncodingManager(), graphHopper.getOSMParsers())
                    .importGeoJson(geoJson, config);
            long importMs = System.currentTimeMillis() - importStart;

            int nodesAdded = graph.getNodes() - nodesBefore;
            int edgesAdded = graph.getEdges() - edgesBefore;

            long indexMs = 0;
            if (nodesAdded > 0 || edgesAdded > 0) {
                long indexStart = System.currentTimeMillis();
                graphHopper.rebuildLocationIndex();
                indexMs = System.currentTimeMillis() - indexStart;
            }

            long chStart = System.currentTimeMillis();
            graphHopper.rebuildCH();
            long chMs = System.currentTimeMillis() - chStart;

            graph.flush();
            graphHopper.getProperties().flush();

            long totalMs = System.currentTimeMillis() - totalStart;
            LOGGER.info("运行时更新路网完成 profile={}, mode={}, nodes+{}={}, edges+{}={}, "
                            + "import={}ms, index={}ms, ch={}ms, total={}ms",
                    graphHopper.getProfileName(), summary.getMode(),
                    nodesAdded, graph.getNodes(), edgesAdded, graph.getEdges(),
                    importMs, indexMs, chMs, totalMs);

            return new UpdateResult(graphHopper.getProfileName(), summary.getMode(),
                    nodesAdded, edgesAdded, graph.getNodes(), graph.getEdges(),
                    summary.getMatchedFeatures(), summary.getMatchedEdges(), summary.getSkipped(),
                    importMs, indexMs, chMs, totalMs);
        } finally {
            if (wasFrozen && !graph.isFrozen()) {
                graph.freeze();
            }
        }
    }

    public static final class UpdateResult {
        private final String profile;
        private final String mode;
        private final int nodesAdded;
        private final int edgesAdded;
        private final int totalNodes;
        private final int totalEdges;
        private final int matchedFeatures;
        private final int matchedEdges;
        private final int skipped;
        private final long importMs;
        private final long indexRebuildMs;
        private final long chRebuildMs;
        private final long totalMs;

        public UpdateResult(String profile, String mode, int nodesAdded, int edgesAdded,
                            int totalNodes, int totalEdges, int matchedFeatures, int matchedEdges,
                            int skipped, long importMs, long indexRebuildMs, long chRebuildMs,
                            long totalMs) {
            this.profile = profile;
            this.mode = mode;
            this.nodesAdded = nodesAdded;
            this.edgesAdded = edgesAdded;
            this.totalNodes = totalNodes;
            this.totalEdges = totalEdges;
            this.matchedFeatures = matchedFeatures;
            this.matchedEdges = matchedEdges;
            this.skipped = skipped;
            this.importMs = importMs;
            this.indexRebuildMs = indexRebuildMs;
            this.chRebuildMs = chRebuildMs;
            this.totalMs = totalMs;
        }

        public String getProfile() {
            return profile;
        }

        public String getMode() {
            return mode;
        }

        public int getNodesAdded() {
            return nodesAdded;
        }

        public int getEdgesAdded() {
            return edgesAdded;
        }

        public int getTotalNodes() {
            return totalNodes;
        }

        public int getTotalEdges() {
            return totalEdges;
        }

        public int getMatchedFeatures() {
            return matchedFeatures;
        }

        public int getMatchedEdges() {
            return matchedEdges;
        }

        public int getSkipped() {
            return skipped;
        }

        public long getImportMs() {
            return importMs;
        }

        public long getIndexRebuildMs() {
            return indexRebuildMs;
        }

        public long getChRebuildMs() {
            return chRebuildMs;
        }

        public long getTotalMs() {
            return totalMs;
        }
    }
}
