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
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 基于 GraphHopper 路网的公路信息提供者：按车辆类型的可通行边查询网格附近的公路及其速度，
 * 供越野混合路由判断路段是公路还是越野。
 */
public class GraphRoadAccessProvider implements RoadAccessProvider {

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
     * 一次性把 bbox 内相交的路网边栅格化到请求网格：遍历边几何并沿线段采样，
     * 写入采样点所在网格的速度与边信息。相比逐格 {@link #getRoad} 的空间索引查询，
     * 既避免了对空旷格子反复扩圈，也不会漏掉窄路。
     */
    @Override
    public RoadGrid rasterizeRoads(GeoBounds bounds, int rows, int cols,
                                   double cellWidth, double cellHeight) {
        int total = rows * cols;
        double[] speedKmh = new double[total];
        int[] edgeIds = new int[total];
        int[] adjNodes = new int[total];
        Arrays.fill(edgeIds, -1);
        Arrays.fill(adjNodes, -1);

        double centerLat = (bounds.minLat + bounds.maxLat) / 2;
        double metersPerDegreeLon = 111320 * Math.cos(Math.toRadians(centerLat));
        // 采样步长取半格，保证斜穿网格的线段不会漏格
        double sampleStep = Math.max(1, 0.5 * Math.min(cellWidth * metersPerDegreeLon, cellHeight * 110540));

        // 位置索引的批量查询接口在部分索引格式下不返回结果，这里直接遍历路网边：
        // 先用两端点做 bbox 粗筛（留 1 km 余量，兼顾跨边界的长边），再栅格化几何。
        double padLat = 1000.0 / 110540;
        double padLon = 1000.0 / Math.max(1, metersPerDegreeLon);
        NodeAccess nodeAccess = baseGraph.getNodeAccess();
        AllEdgesIterator iter = baseGraph.getAllEdges();
        while (iter.next()) {
            int baseNode = iter.getBaseNode();
            int adjNode = iter.getAdjNode();
            double baseLat = nodeAccess.getLat(baseNode);
            double baseLon = nodeAccess.getLon(baseNode);
            double adjLat = nodeAccess.getLat(adjNode);
            double adjLon = nodeAccess.getLon(adjNode);
            if (Math.max(baseLat, adjLat) < bounds.minLat - padLat
                    || Math.min(baseLat, adjLat) > bounds.maxLat + padLat
                    || Math.max(baseLon, adjLon) < bounds.minLon - padLon
                    || Math.min(baseLon, adjLon) > bounds.maxLon + padLon) {
                continue;
            }
            if (!accessFilter.accept(iter)) {
                continue;
            }
            double speed = iter.get(speedEncodedValue);
            if (speed <= 0) {
                continue;
            }
            int edgeId = iter.getEdge();
            PointList geometry = iter.fetchWayGeometry(FetchMode.ALL);
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
                    int col = (int) ((lon1 + deltaLon * t - bounds.minLon) / cellWidth);
                    int row = (int) ((lat1 + deltaLat * t - bounds.minLat) / cellHeight);
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
        return new RoadGrid(speedKmh, edgeIds, adjNodes);
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
