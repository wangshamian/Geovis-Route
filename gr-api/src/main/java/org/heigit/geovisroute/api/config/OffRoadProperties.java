package org.heigit.geovisroute.api.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 越野路径默认参数，对应 gr-config.yml 中的 ors.offroad。
 * 请求级参数（请求体字段或 hint）优先于这里的默认值。
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@Configuration
@ConfigurationProperties(prefix = "ors.offroad")
public class OffRoadProperties {

    /**
     * 纯越野网格数量上限。
     */
    private int maxCells = 2_000_000;
    /**
     * 公路+越野混合路由网格数量上限。
     */
    private int maxHybridCells = 2_000_000;
    /**
     * 单次计算超时（毫秒）。
     */
    private long timeoutMs = 60_000;
    /**
     * 越野基准速度（km/h），仅在请求未指定车型时作为默认车型 offroad-car 的速度。
     */
    private double offroadSpeedKmh = 20;
    /**
     * 混合路由越野段惩罚系数，需不小于 1。
     */
    private double offroadPenalty = 1.5;
    /**
     * 走廊式搜索的最小半宽（米）。
     */
    private double corridorMinMeters = 1500;
    /**
     * 走廊式搜索的最大半宽（米）。
     */
    private double corridorMaxMeters = 8000;
    /**
     * 走廊半宽与起终点直线距离的比例。
     */
    private double corridorRatio = 0.5;
    /**
     * 地表覆盖折减下限，避免越野速度被压到接近 0。
     */
    private double minSurfaceFactor = 0.2;
    /**
     * 混合路由公路段速度下限（km/h）。
     */
    private double minRoadSpeedKmh = 5;
}
