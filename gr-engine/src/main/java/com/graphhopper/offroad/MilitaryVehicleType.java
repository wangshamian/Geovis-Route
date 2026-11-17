package com.graphhopper.offroad;

import java.util.Locale;

/**
 * 军车细分类型：决定越野基准速度、上/下坡衰减强度、公路速度折减与默认公路优先系数。
 */
public enum MilitaryVehicleType {
    /** 轮式军车：机动性好，公路速度不打折，越野爬坡能力中等 */
    WHEELED("wheeled", "轮式军车", 30, 0.25, 0.75, 0.60, 0.30, 1.0, 1.5),
    /** 履带军车：爬坡与越野最强，但公路速度明显偏低且不倾向绕行 */
    TRACKED("tracked", "履带军车", 20, 0.35, 0.55, 0.50, 0.35, 0.55, 1.2),
    /** 后勤运输：基本沿公路机动，越野能力弱、速度低 */
    LOGISTICS("logistics", "后勤运输", 15, 0.20, 0.80, 0.60, 0.30, 0.90, 2.0);

    private final String value;
    private final String label;
    private final double baseSpeedKmh;
    private final double uphillMinFactor;
    private final double uphillDrop;
    private final double downhillMinFactor;
    private final double downhillDrop;
    private final double roadSpeedFactor;
    private final double defaultRoadPenalty;

    MilitaryVehicleType(String value, String label, double baseSpeedKmh,
                        double uphillMinFactor, double uphillDrop,
                        double downhillMinFactor, double downhillDrop,
                        double roadSpeedFactor, double defaultRoadPenalty) {
        this.value = value;
        this.label = label;
        this.baseSpeedKmh = baseSpeedKmh;
        this.uphillMinFactor = uphillMinFactor;
        this.uphillDrop = uphillDrop;
        this.downhillMinFactor = downhillMinFactor;
        this.downhillDrop = downhillDrop;
        this.roadSpeedFactor = roadSpeedFactor;
        this.defaultRoadPenalty = defaultRoadPenalty;
    }

    public static MilitaryVehicleType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return WHEELED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MilitaryVehicleType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("military_type 仅支持 wheeled、tracked、logistics");
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public double getBaseSpeedKmh() {
        return baseSpeedKmh;
    }

    public double getUphillMinFactor() {
        return uphillMinFactor;
    }

    public double getUphillDrop() {
        return uphillDrop;
    }

    public double getDownhillMinFactor() {
        return downhillMinFactor;
    }

    public double getDownhillDrop() {
        return downhillDrop;
    }

    public double getRoadSpeedFactor() {
        return roadSpeedFactor;
    }

    public double getDefaultRoadPenalty() {
        return defaultRoadPenalty;
    }
}
