package com.graphhopper.offroad;

import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;

import java.util.List;

public class OffRoadGrid {
    protected final int x;
    protected final int y;
    protected final GHPoint center;
    protected final double slope;
    /**
     * 网格中心高程（米），用于区分上坡/下坡的通行速度衰减。
     */
    protected final double elevation;
    protected final List<GHPoint> coordinates;
    protected String id;
    protected String parentId;
    protected double h = 0;
    protected double g = 0;
    protected double f = 0;
    protected boolean isInCloseList;
    protected boolean isInOpenList;
    /**
     * 是否落在越野避让要素上（水体、建筑、铁路等），为 true 时不可通行。
     */
    protected boolean blocked;
    /**
     * 网格中心附近公路的通行速度（km/h），0 表示该网格不在公路上。
     */
    protected double roadSpeedKmh = 0;
    /**
     * 网格所在公路的类型描述（如「主干道」「桥梁」），空串表示不在公路上。
     */
    protected String roadLabel = "";
    /**
     * 网格所在公路的名称（如「京藏高速」），空串表示不在公路上或路网无名称。
     */
    protected String roadName = "";
    /**
     * 网格吸附到的路网边及吸附点，用于按真实道路几何计算行进方向。
     */
    protected int roadEdgeId = -1;
    protected int roadAdjNode = -1;
    protected double roadSnapLat = 0;
    protected double roadSnapLon = 0;

    public OffRoadGrid(int x, int y, double slope, double elevation, GHPoint center, List<GHPoint> coordinates) {
        this.x = x;
        this.y = y;
        this.id = x + "-" + y;
        this.slope = slope;
        this.elevation = elevation;
        this.center = center;
        this.coordinates = coordinates;
    }
}
