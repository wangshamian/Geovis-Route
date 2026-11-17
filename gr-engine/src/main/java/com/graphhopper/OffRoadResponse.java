package com.graphhopper;

import com.graphhopper.util.JsonFeature;
import com.graphhopper.util.PMap;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wrapper containing path and error output of OffRoad.
 */
public class OffRoadResponse{
    private final List<Throwable> errors = new ArrayList<>(4);
    private PMap hintsMap = new PMap();
    private PointList offRoadPoints;
    private JsonFeature barrierFeature;
    // 坡度超限的网格数量（合并前）
    private int barrierCount;
    // 混合路由：累计用时（秒）与公路/越野里程（米）
    private long durationSeconds;
    private double roadDistance;
    private double offRoadDistance;
    // 混合路由分段明细：按公路/越野连续段切分
    private final List<Segment> segments = new ArrayList<>();
    // 因地表覆盖禁行（水体/湿地/冰雪等）被排除的网格数量与类别分布
    private int blockedCellCount;
    private final Map<String, Integer> blockedSurfaceCounts = new LinkedHashMap<>();
    // 纯越野路径经过的地表构成，按里程从多到少
    private List<SurfaceSlice> pathSurfaces = new ArrayList<>();
//    private String debugInfo = "";

    public OffRoadResponse() {
    }

    public void addOffRoadPoints(PointList offRoadPoints) {
        this.offRoadPoints = offRoadPoints;
    }

    public PointList getOffRoadPoints() {
        return offRoadPoints;
    }

    public void addBarrierFeature(JsonFeature barrierFeature) {
        this.barrierFeature = barrierFeature;
    }

    public JsonFeature getBarrierFeature() {
        return barrierFeature;
    }

    public void setBarrierCount(int barrierCount) {
        this.barrierCount = barrierCount;
    }

    public int getBarrierCount() {
        return barrierCount;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public double getRoadDistance() {
        return roadDistance;
    }

    public void setRoadDistance(double roadDistance) {
        this.roadDistance = roadDistance;
    }

    public double getOffRoadDistance() {
        return offRoadDistance;
    }

    public void setOffRoadDistance(double offRoadDistance) {
        this.offRoadDistance = offRoadDistance;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public void addSegment(Segment segment) {
        this.segments.add(segment);
    }

    public int getBlockedCellCount() {
        return blockedCellCount;
    }

    /**
     * 记录一个因地表禁行而被排除的网格，按类别名称累计数量。
     */
    public void addBlockedCell(String surfaceLabel) {
        blockedCellCount++;
        String key = surfaceLabel == null ? "未知地表" : surfaceLabel;
        blockedSurfaceCounts.merge(key, 1, Integer::sum);
    }

    public Map<String, Integer> getBlockedSurfaceCounts() {
        return blockedSurfaceCounts;
    }

    public List<SurfaceSlice> getPathSurfaces() {
        return pathSurfaces;
    }

    public void setPathSurfaces(List<SurfaceSlice> pathSurfaces) {
        this.pathSurfaces = pathSurfaces == null ? new ArrayList<>() : pathSurfaces;
    }

    /**
     * 一段连续同类地表及其里程与速度折减系数。
     */
    public static class SurfaceSlice {
        private final String label;
        private final double speedFactor;
        private double distance;

        public SurfaceSlice(String label, double speedFactor) {
            this.label = label;
            this.speedFactor = speedFactor;
        }

        public void addDistance(double meters) {
            this.distance += meters;
        }

        public String getLabel() {
            return label;
        }

        public double getSpeedFactor() {
            return speedFactor;
        }

        public double getDistance() {
            return distance;
        }
    }

    /**
     * 混合路由中的一段连续公路或越野路段。
     */
    public static class Segment {
        private final boolean onRoad;
        private final double distance;
        private final long duration;
        private final double startLat;
        private final double startLon;
        private final double endLat;
        private final double endLon;
        private final double avgSlope;
        private final double maxSlope;
        private final double ascent;
        private final double descent;
        private final String roadLabel;
        private final String reason;
        // 该段经过的公路名称（按出现顺序去重），越野段为空
        private final List<String> roadNames;
        // 该段的行进方向切片，如「向东北 0.8 km」
        private final List<DirectionSlice> directions;
        // 该段经过的网格中心点，供前端按段着色
        private final List<GHPoint> points;
        // 越野段经过的地表构成（林地、建成区等）与相应速度折减
        private List<SurfaceSlice> surfaces;

        public Segment(boolean onRoad, double distance, long duration,
                       double startLat, double startLon, double endLat, double endLon,
                       double avgSlope, double maxSlope, double ascent, double descent,
                       String roadLabel, List<String> roadNames, List<DirectionSlice> directions,
                       String reason, List<GHPoint> points) {
            this.onRoad = onRoad;
            this.distance = distance;
            this.duration = duration;
            this.startLat = startLat;
            this.startLon = startLon;
            this.endLat = endLat;
            this.endLon = endLon;
            this.avgSlope = avgSlope;
            this.maxSlope = maxSlope;
            this.ascent = ascent;
            this.descent = descent;
            this.roadLabel = roadLabel == null ? "" : roadLabel;
            this.roadNames = roadNames == null ? new ArrayList<>() : new ArrayList<>(roadNames);
            this.directions = directions == null ? new ArrayList<>() : new ArrayList<>(directions);
            this.reason = reason == null ? "" : reason;
            this.points = points;
            this.surfaces = new ArrayList<>();
        }

        public List<SurfaceSlice> getSurfaces() {
            return surfaces;
        }

        public void setSurfaces(List<SurfaceSlice> surfaces) {
            this.surfaces = surfaces == null ? new ArrayList<>() : surfaces;
        }

        public boolean isOnRoad() {
            return onRoad;
        }

        public double getDistance() {
            return distance;
        }

        public long getDuration() {
            return duration;
        }

        public double getStartLat() {
            return startLat;
        }

        public double getStartLon() {
            return startLon;
        }

        public double getEndLat() {
            return endLat;
        }

        public double getEndLon() {
            return endLon;
        }

        public List<GHPoint> getPoints() {
            return points;
        }

        public double getAvgSlope() {
            return avgSlope;
        }

        public double getMaxSlope() {
            return maxSlope;
        }

        public double getAscent() {
            return ascent;
        }

        public double getDescent() {
            return descent;
        }

        public String getRoadLabel() {
            return roadLabel;
        }

        public List<String> getRoadNames() {
            return roadNames;
        }

        public List<DirectionSlice> getDirections() {
            return directions;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * 行进方向切片：同一方位连续行驶的一段距离。
     */
    public static class DirectionSlice {
        private final String label;
        private double distance;

        public DirectionSlice(String label, double distance) {
            this.label = label;
            this.distance = distance;
        }

        public void addDistance(double meters) {
            this.distance += meters;
        }

        public String getLabel() {
            return label;
        }

        public double getDistance() {
            return distance;
        }
    }

    public void setHints(PMap hints) {
        this.hintsMap = hints;
    }

    public PMap getHints() {
        return hintsMap;
    }

    public String getHeader(String key, String defaultValue) {
        Object val = hintsMap.getObject(key.toLowerCase(Locale.ROOT), null);
        if (val instanceof List && !((List) val).isEmpty())
            return ((List) val).get(0).toString();
        return defaultValue;
    }
}
