package org.heigit.geovisroute.routing.graphhopper.extensions.offroad;

import com.graphhopper.GraphHopper;
import com.graphhopper.offroad.RoadAccessProvider;
import com.graphhopper.offroad.util.GeoBounds;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.AccessFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 基于 GraphHopper 路网的公路信息提供者：按车辆类型的可通行边查询网格附近的公路及其速度，
 * 供越野混合路由判断路段是公路还是越野。
 */
public class GraphRoadAccessProvider implements RoadAccessProvider {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(GraphRoadAccessProvider.class);

    private final LocationIndex locationIndex;
    private final BaseGraph baseGraph;
    private final EdgeFilter accessFilter;
    private final DecimalEncodedValue speedEncodedValue;
    /**
     * 道路等级与道路环境，用于给公路段补充可读描述（路网未存道路名称）。
     */
    private final EnumEncodedValue<RoadClass> roadClassEncodedValue;
    private final EnumEncodedValue<RoadEnvironment> roadEnvironmentEncodedValue;
    /**
     * 网格中心到公路边的最大吸附距离（米），超过则认为该网格不在公路上。
     */
    private final double maxSnapDistance;
    /**
     * 瓦片级边索引的瓦片边长（度）与端点包围盒外扩量（度）。
     */
    private static final double EDGE_TILE_DEGREES = 0.01;
    private static final double EDGE_TILE_PADDING_DEGREES = 0.002;
    /**
     * 瓦片键到边 ID 列表的索引。Provider 每次请求都会新建，因此按 BaseGraph 缓存，
     * 保证索引在服务生命周期内只构建一次；BaseGraph 被回收后对应条目自动失效。
     */
    private static final WeakHashMap<BaseGraph, LongObjectHashMap<int[]>> EDGE_TILE_INDEX_CACHE =
            new WeakHashMap<>();

    public GraphRoadAccessProvider(GraphHopper graphHopper, String vehicle, double maxSnapDistance) {
        this.locationIndex = graphHopper.getLocationIndex();
        this.baseGraph = graphHopper.getBaseGraph();
        EncodingManager encodingManager = graphHopper.getEncodingManager();
        String accessKey = VehicleAccess.key(vehicle);
        String speedKey = VehicleSpeed.key(vehicle);
        if (!encodingManager.hasEncodedValue(accessKey) || !encodingManager.hasEncodedValue(speedKey)) {
            throw new IllegalArgumentException("路网缺少编码值 " + speedKey + "，无法进行公路与越野混合路由");
        }
        this.accessFilter = AccessFilter.allEdges(encodingManager.getBooleanEncodedValue(accessKey));
        this.speedEncodedValue = encodingManager.getDecimalEncodedValue(speedKey);
        this.roadClassEncodedValue = encodingManager.hasEncodedValue(RoadClass.KEY)
                ? encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class) : null;
        this.roadEnvironmentEncodedValue = encodingManager.hasEncodedValue(RoadEnvironment.KEY)
                ? encodingManager.getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class) : null;
        this.maxSnapDistance = maxSnapDistance;
    }

    @Override
    public RoadAccess getRoad(double lat, double lon) {
        Snap snap = locationIndex.findClosest(lat, lon, accessFilter);
        if (!snap.isValid() || snap.getQueryDistance() > maxSnapDistance) {
            return null;
        }
        EdgeIteratorState closestEdge = snap.getClosestEdge();
        double speedKmh = closestEdge.get(speedEncodedValue);
        if (speedKmh <= 0) {
            return null;
        }
        return new RoadAccess(speedKmh, closestEdge.getEdge(), closestEdge.getAdjNode(),
                snap.getSnappedPoint().lat, snap.getSnappedPoint().lon);
    }

    /**
     * 解析道路类型与名称。只在最终路径的网格上调用，避免建图阶段逐格读取路网属性。
     */
    @Override
    public RoadMeta getRoadMeta(int edgeId, int adjNode) {
        EdgeIteratorState edge = baseGraph.getEdgeIteratorState(edgeId, adjNode);
        if (edge == null) {
            return RoadMeta.EMPTY;
        }
        return new RoadMeta(
                roadLabel(roadClassEncodedValue == null ? RoadClass.OTHER : edge.get(roadClassEncodedValue),
                        roadEnvironmentEncodedValue == null ? RoadEnvironment.ROAD : edge.get(roadEnvironmentEncodedValue)),
                roadName(edge));
    }

    /**
     * 一次性把 bbox 内相交的路网边栅格化到请求网格：按瓦片索引取出候选边，
     * 再沿边几何按半格步长采样，写入采样点所在网格的速度与边信息。
     */
    @Override
    public RoadGrid rasterizeRoads(GeoBounds bounds, int rows, int cols,
                                   double cellWidth, double cellHeight) {
        RoadRasterizer rasterizer = new RoadRasterizer(bounds, rows, cols, cellWidth, cellHeight);

        double padLat = 1000.0 / 110540;
        double padLon = 1000.0 / Math.max(1, rasterizer.metersPerDegreeLon);
        LongObjectHashMap<int[]> index = edgeTileIndex();
        IntHashSet seen = new IntHashSet();
        int minTileX = tileX(bounds.minLon - padLon);
        int maxTileX = tileX(bounds.maxLon + padLon);
        int minTileY = tileY(bounds.minLat - padLat);
        int maxTileY = tileY(bounds.maxLat + padLat);
        for (int tileXValue = minTileX; tileXValue <= maxTileX; tileXValue++) {
            for (int tileYValue = minTileY; tileYValue <= maxTileY; tileYValue++) {
                int[] edgeIds = index.get(tileKey(tileXValue, tileYValue));
                if (edgeIds == null) {
                    continue;
                }
                for (int edgeId : edgeIds) {
                    // 同一条边可能被多个瓦片收录，这里去重
                    if (!seen.add(edgeId)) {
                        continue;
                    }
                    EdgeIteratorState edge = baseGraph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
                    if (edge != null) {
                        rasterizer.addEdge(edge, edge.get(speedEncodedValue));
                    }
                }
            }
        }
        return rasterizer.toGrid();
    }

    /**
     * 获取瓦片级路网边索引，首次调用时构建，之后只读复用。
     * 路网在服务运行期间不变，因此无需失效机制。
     */
    private LongObjectHashMap<int[]> edgeTileIndex() {
        synchronized (EDGE_TILE_INDEX_CACHE) {
            LongObjectHashMap<int[]> index = EDGE_TILE_INDEX_CACHE.get(baseGraph);
            if (index == null) {
                index = buildEdgeTileIndex();
                EDGE_TILE_INDEX_CACHE.put(baseGraph, index);
            }
            return index;
        }
    }

    /**
     * 扫描一次路网，把每条公路边登记到它几何包围盒覆盖的所有瓦片上。
     * 一次扫描的成本与单次全量栅格化相当，但之后每次请求只需处理相交瓦片。
     */
    private LongObjectHashMap<int[]> buildEdgeTileIndex() {
        long startNanos = System.nanoTime();
        LongObjectHashMap<IntArrayList> buckets = new LongObjectHashMap<>();
        AllEdgesIterator iter = baseGraph.getAllEdges();
        while (iter.next()) {
            if (!accessFilter.accept(iter) || iter.get(speedEncodedValue) <= 0) {
                continue;
            }
            // 用真实几何的包围盒登记，避免弯曲路段超出端点范围而漏掉相邻瓦片
            PointList geometry = iter.fetchWayGeometry(FetchMode.ALL);
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (int i = 0; i < geometry.size(); i++) {
                double lat = geometry.getLat(i);
                double lon = geometry.getLon(i);
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
            }
            int minTileX = tileX(minLon - EDGE_TILE_PADDING_DEGREES);
            int maxTileX = tileX(maxLon + EDGE_TILE_PADDING_DEGREES);
            int minTileY = tileY(minLat - EDGE_TILE_PADDING_DEGREES);
            int maxTileY = tileY(maxLat + EDGE_TILE_PADDING_DEGREES);
            int edgeId = iter.getEdge();
            for (int tileXValue = minTileX; tileXValue <= maxTileX; tileXValue++) {
                for (int tileYValue = minTileY; tileYValue <= maxTileY; tileYValue++) {
                    long key = tileKey(tileXValue, tileYValue);
                    IntArrayList list = buckets.get(key);
                    if (list == null) {
                        list = new IntArrayList(2);
                        buckets.put(key, list);
                    }
                    list.add(edgeId);
                }
            }
        }
        LongObjectHashMap<int[]> index = new LongObjectHashMap<>(buckets.size());
        for (LongObjectCursor<IntArrayList> cursor : buckets) {
            index.put(cursor.key, cursor.value.toArray());
        }
        LOGGER.info("[offroad-road-index] tiles={} build={}ms", index.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
        return index;
    }

    private static int tileX(double lon) {
        return (int) Math.floor(lon / EDGE_TILE_DEGREES);
    }

    private static int tileY(double lat) {
        return (int) Math.floor(lat / EDGE_TILE_DEGREES);
    }

    private static long tileKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    /**
     * 把单条公路边的几何栅格化到请求网格。
     */
    private static final class RoadRasterizer {
        private final double minLon;
        private final double minLat;
        private final int rows;
        private final int cols;
        private final double cellWidth;
        private final double cellHeight;
        private final double sampleStep;
        private final double metersPerDegreeLon;
        private final double[] speedKmh;
        private final int[] edgeIds;
        private final int[] adjNodes;

        RoadRasterizer(GeoBounds bounds, int rows, int cols, double cellWidth, double cellHeight) {
            this.minLon = bounds.minLon;
            this.minLat = bounds.minLat;
            this.rows = rows;
            this.cols = cols;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.speedKmh = new double[rows * cols];
            this.edgeIds = new int[rows * cols];
            this.adjNodes = new int[rows * cols];
            Arrays.fill(edgeIds, -1);
            Arrays.fill(adjNodes, -1);
            double centerLat = (bounds.minLat + bounds.maxLat) / 2;
            this.metersPerDegreeLon = 111320 * Math.cos(Math.toRadians(centerLat));
            // 采样步长取半格，保证斜穿网格的线段不会漏格
            this.sampleStep = Math.max(1, 0.5 * Math.min(cellWidth * metersPerDegreeLon, cellHeight * 110540));
        }

        void addEdge(EdgeIteratorState edge, double speed) {
            if (speed <= 0) {
                return;
            }
            int edgeId = edge.getEdge();
            int adjNode = edge.getAdjNode();
            PointList geometry = edge.fetchWayGeometry(FetchMode.ALL);
            for (int i = 0; i + 1 < geometry.size(); i++) {
                double lat1 = geometry.getLat(i);
                double lon1 = geometry.getLon(i);
                double lat2 = geometry.getLat(i + 1);
                double lon2 = geometry.getLon(i + 1);
                double deltaLat = lat2 - lat1;
                double deltaLon = lon2 - lon1;
                double segmentMeters = Math.hypot(deltaLat * 110540,
                        deltaLon * 111320 * Math.cos(Math.toRadians((lat1 + lat2) / 2)));
                int steps = Math.max(1, (int) Math.ceil(segmentMeters / sampleStep));
                for (int s = 0; s <= steps; s++) {
                    double t = (double) s / steps;
                    int col = (int) ((lon1 + deltaLon * t - minLon) / cellWidth);
                    int row = (int) ((lat1 + deltaLat * t - minLat) / cellHeight);
                    if (col < 0 || col >= cols || row < 0 || row >= rows) {
                        continue;
                    }
                    int index = row * cols + col;
                    // 同一网格被多条公路穿过时取速度最高的一条，等价于优先走最快的路
                    if (speed > speedKmh[index]) {
                        speedKmh[index] = speed;
                        edgeIds[index] = edgeId;
                        adjNodes[index] = adjNode;
                    }
                }
            }
        }

        RoadGrid toGrid() {
            return new RoadGrid(speedKmh, edgeIds, adjNodes);
        }
    }

    /**
     * 取公路边的真实几何点串，供按道路几何计算行进方向。
     */
    @Override
    public List<GHPoint> getEdgeGeometry(int edgeId, int adjNode) {
        EdgeIteratorState edge = baseGraph.getEdgeIteratorState(edgeId, adjNode);
        if (edge == null) {
            return Collections.emptyList();
        }
        PointList geometry = edge.fetchWayGeometry(FetchMode.ALL);
        List<GHPoint> points = new ArrayList<>(geometry.size());
        for (int i = 0; i < geometry.size(); i++) {
            points.add(new GHPoint(geometry.getLat(i), geometry.getLon(i)));
        }
        return points;
    }

    /**
     * 取道路名称：优先 OSM name，缺失时退回 ref（如 G318）。
     * 名称在导入时写入图的 KVStorage，无需额外的编码值。
     */
    private String roadName(EdgeIteratorState edge) {
        String name = edge.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        Object ref = edge.getValue(Parameters.Details.STREET_REF);
        return ref == null ? "" : ref.toString();
    }

    private String roadLabel(RoadClass roadClass, RoadEnvironment environment) {
        if (environment == RoadEnvironment.FERRY) {
            return "轮渡";
        }
        String base = switch (roadClass) {
            case MOTORWAY -> "高速公路";
            case TRUNK -> "快速路";
            case PRIMARY -> "主干道";
            case SECONDARY -> "次干道";
            case TERTIARY -> "支路";
            case RESIDENTIAL -> "居住区道路";
            case SERVICE -> "服务道路";
            case TRACK -> "土路";
            case CYCLEWAY -> "自行车道";
            case FOOTWAY, PEDESTRIAN, STEPS -> "步行道";
            case PATH -> "小径";
            default -> "道路";
        };
        if (environment == RoadEnvironment.TUNNEL) {
            return base + "（隧道）";
        }
        if (environment == RoadEnvironment.BRIDGE) {
            return base + "（桥梁）";
        }
        return base;
    }
}
