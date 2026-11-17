package com.graphhopper.offroad;

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
     * 公路信息：通行速度、道路类型描述、道路名称与吸附到的路网边。
     *
     * @param roadName 道路名称（OSM name/ref），取不到时为空串
     * @param edgeId   吸附到的路网边 ID，-1 表示无
     * @param adjNode  边的另一端点，与 edgeId 一起确定边的方向
     * @param snapLat  吸附点纬度
     * @param snapLon  吸附点经度
     */
    record RoadAccess(double speedKmh, String roadLabel, String roadName,
                      int edgeId, int adjNode, double snapLat, double snapLon) {
    }
}
