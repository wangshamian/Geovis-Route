package com.graphhopper.offroad;

import com.graphhopper.offroad.util.GeoBounds;
import com.graphhopper.util.shapes.GHPoint;

import java.util.Collections;
import java.util.List;

/**
 * 公路信息提供者：混合路由时用于判断网格是否落在公路上，并给出该处公路的通行速度。
 */
public interface RoadAccessProvider {

    /**
     * 查询给定坐标附近的公路。
     *
     * @return 附近没有可通行公路时返回 null
     */
    RoadAccess getRoad(double lat, double lon);

    /**
     * 查询公路边的真实几何点串，用于按道路几何计算行进方向。
     * 默认实现返回空列表，表示不提供道路几何。
     */
    default List<GHPoint> getEdgeGeometry(int edgeId, int adjNode) {
        return Collections.emptyList();
    }

    /**
     * 查询道路类型与名称。道路名称需要读路网属性，成本较高，
     * 建图阶段不调用，只在最终路径的少量网格上补齐。
     * 默认实现返回空信息，表示不提供道路描述。
     */
    default RoadMeta getRoadMeta(int edgeId, int adjNode) {
        return RoadMeta.EMPTY;
    }

    /**
     * 把 bbox 内的公路一次性栅格化到规则网格上，返回按网格索引（row * cols + col）存放的
     * 速度、边 ID 与端点。相比逐格 {@link #getRoad}，避免了对远离公路的空旷格子反复扩圈搜索。
     * 默认返回 null，表示不支持栅格化，调用方回退到逐格查询。
     */
    default RoadGrid rasterizeRoads(GeoBounds bounds, int rows, int cols,
                                    double cellWidth, double cellHeight) {
        return null;
    }

    /**
     * 公路栅格化结果，三个数组长度均为 rows * cols，速度 0 表示该网格不在公路上。
     */
    record RoadGrid(double[] speedKmh, int[] edgeId, int[] adjNode) {
    }

    /**
     * 公路信息：通行速度与吸附到的路网边。
     *
     * @param edgeId  吸附到的路网边 ID，-1 表示无
     * @param adjNode 边的另一端点，与 edgeId 一起确定边的方向
     * @param snapLat 吸附点纬度
     * @param snapLon 吸附点经度
     */
    record RoadAccess(double speedKmh, int edgeId, int adjNode, double snapLat, double snapLon) {
    }

    /**
     * 道路类型与名称，用于路径分段展示。
     *
     * @param roadName 道路名称（OSM name/ref），取不到时为空串
     */
    record RoadMeta(String roadLabel, String roadName) {
        public static final RoadMeta EMPTY = new RoadMeta("", "");
    }
}
