package com.graphhopper.offroad;

/**
 * 越野通行约束提供者：判断某个坐标是否不可通行，以及地表类型对越野速度的折减。
 * 当前实现由 ESA WorldCover 地表覆盖栅格提供（建成区、水体、湿地、红树林等禁行）。
 */
public interface ObstacleProvider {
    /**
     * @return true 表示该坐标不可通行
     */
    boolean isBlocked(double lat, double lon);

    /**
     * 地表类型名称（如「林地」「水体」），用于在路径结果中说明减速或绕行原因。
     * 默认返回 null 表示无地表分类信息。
     */
    default String surfaceName(double lat, double lon) {
        return null;
    }

    /**
     * 地表类型对越野速度的折减系数，1.0 表示不折减。
     * 默认不折减，仅面状地表数据（如 WorldCover）需要覆盖。
     */
    default double speedFactor(double lat, double lon) {
        return 1.0;
    }
}
