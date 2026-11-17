package org.heigit.geovisroute.routing.graphhopper.extensions.offroad;

import com.graphhopper.GraphHopper;
import com.graphhopper.offroad.RoadAccessProvider;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.AccessFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
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
        double speedKmh = snap.getClosestEdge().get(speedEncodedValue);
        if (speedKmh <= 0) {
            return null;
        }
        EdgeIteratorState closestEdge = snap.getClosestEdge();
        return new RoadAccess(speedKmh, roadLabel(
                roadClassEncodedValue == null ? RoadClass.OTHER : closestEdge.get(roadClassEncodedValue),
                roadEnvironmentEncodedValue == null ? RoadEnvironment.ROAD : closestEdge.get(roadEnvironmentEncodedValue)),
                roadName(closestEdge), closestEdge.getEdge(), closestEdge.getAdjNode(),
                snap.getSnappedPoint().lat, snap.getSnappedPoint().lon);
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
