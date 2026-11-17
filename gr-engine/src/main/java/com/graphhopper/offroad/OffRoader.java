package com.graphhopper.offroad;

import com.graphhopper.GHRequest;
import com.graphhopper.OffRoadResponse;
import com.graphhopper.offroad.util.GeoBounds;
import com.graphhopper.offroad.util.GeoUtils;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.util.JsonFeature;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import org.gavaghan.geodesy.GlobalCoordinates;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class OffRoader {
    /**
     * 走廊最小/最大半宽（米）：搜索范围限制在起终点连线两侧一定范围内，避免整片矩形区域都参与计算。
     */
    private static final double MIN_CORRIDOR_METERS = 1500;
    private static final double MAX_CORRIDOR_METERS = 8000;
    /**
     * 走廊半宽与起终点直线距离的比例。
     */
    private static final double CORRIDOR_RATIO = 0.5;
    /**
     * 默认最大可通行坡度（度）。
     */
    private static final double DEFAULT_MAX_SLOPE = 20;
    /**
     * 默认最多网格数量与超时时间。
     */
    private static final int DEFAULT_MAX_CELLS = 250_000;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    /**
     * 混合路由需要逐格查询公路，网格数量上限单独收紧。
     */
    private static final int MAX_HYBRID_CELLS = 60_000;

    protected final ElevationProvider eleProvider;
    /**
     * 公路信息提供者，非空且请求开启 hybrid 时执行公路与越野混合寻路。
     */
    protected final RoadAccessProvider roadAccessProvider;
    protected OffRoadGrid startGrid;
    protected OffRoadGrid endGrid;
    private double maxSlope = DEFAULT_MAX_SLOPE;
    private GHPoint startPoint;
    private GHPoint endPoint;
    /**
     * 混合路由参数：是否启用、越野基准速度、越野段惩罚系数。
     */
    private boolean hybrid;
    private double offroadSpeedKmh = 25;
    private double offroadPenalty = 1.5;
    /**
     * 军车细分类型，决定上/下坡衰减强度与公路速度折减。
     */
    private MilitaryVehicleType militaryVehicleType = MilitaryVehicleType.WHEELED;
    /**
     * 地表覆盖约束提供者（WorldCover），决定禁行网格与越野速度折减，为空时不启用。
     */
    private ObstacleProvider obstacleProvider;
    /**
     * 混合路由按时间搜索时使用的网格边长（米）与全图最大速度（km/h）。
     */
    private double cellSideMeters = 300;
    private double maxSpeedKmh = 25;
    /**
     * 八邻域偏移：{dx, dy}，前四个为正交方向，后四个为斜向方向。
     */
    private static final int[][] NEIGHBOR_OFFSETS = {
            {0, -1}, {1, 0}, {0, 1}, {-1, 0}, {-1, -1}, {1, -1}, {1, 1}, {-1, 1}
    };

    public OffRoader(ElevationProvider eleProvider) {
        this(eleProvider, null);
    }

    public OffRoader(ElevationProvider eleProvider, RoadAccessProvider roadAccessProvider) {
        this.eleProvider = eleProvider;
        this.roadAccessProvider = roadAccessProvider;
    }

    public void setMilitaryVehicleType(MilitaryVehicleType militaryVehicleType) {
        if (militaryVehicleType != null) {
            this.militaryVehicleType = militaryVehicleType;
        }
    }

    public void setObstacleProvider(ObstacleProvider obstacleProvider) {
        this.obstacleProvider = obstacleProvider;
    }

    public OffRoadResponse offroad(GHRequest request) {
        OffRoadResponse offRoadResponse = new OffRoadResponse();
        double gridInterval = request.getHints().getDouble("grid_interval", 300);
        // 坡度阈值、网格数量上限与超时时间均可通过 hint 配置，便于前端按需调整
        this.maxSlope = request.getHints().getDouble("max_slope", DEFAULT_MAX_SLOPE);
        int maxCells = request.getHints().getInt("max_cells", DEFAULT_MAX_CELLS);
        long timeoutMs = request.getHints().getLong("timeout_ms", DEFAULT_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + timeoutMs;
        // 公路与越野混合：有公路信息提供者且请求显式开启时生效
        this.hybrid = roadAccessProvider != null && request.getHints().getBool("hybrid", false);
        this.offroadSpeedKmh = Math.max(1, request.getHints().getDouble("offroad_speed_kmh", 25));
        this.offroadPenalty = Math.max(1, request.getHints().getDouble("offroad_penalty", 1.5));
        this.cellSideMeters = gridInterval;
        this.maxSpeedKmh = offroadSpeedKmh;

        List<GHPoint> points = request.getPoints();
        this.startPoint = points.get(0);
        this.endPoint = points.get(points.size() - 1);

        // 起终点顺序任意，构造边界时统一按最小值/最大值归一化，避免出现负的跨度
        GeoBounds geoBounds = new GeoBounds(
                Math.min(startPoint.lon, endPoint.lon), Math.max(startPoint.lon, endPoint.lon),
                Math.min(startPoint.lat, endPoint.lat), Math.max(startPoint.lat, endPoint.lat));

        // 走廊式搜索：以起终点直线距离的一半作为搜索半宽，并限制在固定上下界内，
        // 相比按比例放大整片矩形，可显著减少参与计算的网格数量。
        double directDistance = GeoUtils.getDistance(
                new GlobalCoordinates(startPoint.lat, startPoint.lon),
                new GlobalCoordinates(endPoint.lat, endPoint.lon));
        double halfWidth = Math.min(MAX_CORRIDOR_METERS, Math.max(MIN_CORRIDOR_METERS, directDistance * CORRIDOR_RATIO));
        // 保证走廊至少覆盖几个网格，避免极短路径时搜索空间过窄
        halfWidth = Math.max(halfWidth, gridInterval * 3);
        GeoBounds corridor = geoBounds.expandBoundsByMeters(halfWidth);

        List<OffRoadGrid> gridCells = createOffRoadGrids(corridor, gridInterval, this.eleProvider, startPoint,
                endPoint, maxCells, deadline, timeoutMs);
        if (gridCells.isEmpty()) {
            return offRoadResponse;
        }
        return calOffRoadPath(offRoadResponse, gridCells, gridInterval, deadline, timeoutMs);
    }

    /**
     * 获取grid
     */
    public List<OffRoadGrid> createOffRoadGrids(GeoBounds geoBounds, double cellSide, ElevationProvider eleProvider,
                                                GHPoint startPoint, GHPoint endPoint, int maxCells, long deadline,
                                                long timeoutMs) {
        int[][] offRoadGridSteps = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {-1, 3}, {-2, 3}, {-3, 3},
                {-3, 2}, {-3, 1}, {-3, 0}, {-2, 0}, {-1, 0}};
        double lonDistance = GeoUtils.getDistance(new GlobalCoordinates(geoBounds.maxLat, geoBounds.minLon),
                new GlobalCoordinates(geoBounds.maxLat, geoBounds.maxLon));
        double latDistance = GeoUtils.getDistance(new GlobalCoordinates(geoBounds.maxLat, geoBounds.minLon),
                new GlobalCoordinates(geoBounds.minLat, geoBounds.minLon));
        // rows 为纬度方向（y）的网格行数，cols 为经度方向（x）的网格列数
        int rows = Math.max(1, (int) Math.ceil(latDistance / cellSide));
        int cols = Math.max(1, (int) Math.ceil(lonDistance / cellSide));

        // 网格数量保护：超过上限直接拒绝，避免内存与耗时失控
        long totalCells = (long) rows * cols;
        if (totalCells > maxCells) {
            throw new IllegalArgumentException(String.format(
                    "网格数量 %d 超过上限 %d，请增大 grid_interval 或缩短起终点距离", totalCells, maxCells));
        }
        // 混合路由需要逐格查询附近公路，网格过多会显著拖慢计算
        if (hybrid && totalCells > MAX_HYBRID_CELLS) {
            throw new IllegalArgumentException(String.format(
                    "混合路由网格数量 %d 超过上限 %d，请增大 grid_interval 或缩短起终点距离", totalCells, MAX_HYBRID_CELLS));
        }
        this.cellSideMeters = cellSide;

        List<OffRoadGrid> grids = new ArrayList<>((int) totalCells);
        double cellWidth = geoBounds.getWidth() / cols;
        double cellHeight = geoBounds.getHeight() / rows;
        this.startGrid = null;
        this.endGrid = null;

        // 开始计算OffRoadGrid
        int processed = 0;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                // 每处理一小批网格检查一次超时，避免高程采样阶段无限占用线程
                if ((processed++ & 127) == 0 && System.currentTimeMillis() > deadline) {
                    throw new IllegalArgumentException("越野路径计算超时（超过 " + timeoutMs
                            + " 毫秒），请增大网格间距或缩短起终点距离");
                }
                double minX = geoBounds.minLon + j * cellWidth;
                double maxX = minX + cellWidth;
                double minY = geoBounds.minLat + i * cellHeight;
                double maxY = minY + cellHeight;

                // 获取Grid的属性值
                GHPoint center = new GHPoint((minY + maxY) / 2, (minX + maxX) / 2);
                List<GHPoint> coordinates = new ArrayList<>();
                coordinates.add(new GHPoint(minY, minX));
                coordinates.add(new GHPoint(minY, maxX));
                coordinates.add(new GHPoint(maxY, maxX));
                coordinates.add(new GHPoint(maxY, minX));
                coordinates.add(new GHPoint(minY, minX));
                // 采样并计算坡度
                List<GlobalCoordinates> sampleCoordinates = new ArrayList<>();
                double sampleDeltaX = cellWidth / 3;
                double sampleDeltaY = cellHeight / 3;
                for (int k = 0; k < 12; k++) {
                    sampleCoordinates.add(new GlobalCoordinates(minY + offRoadGridSteps[k][0] * sampleDeltaY,
                            minX + offRoadGridSteps[k][1] * sampleDeltaX));
                }
                double slope = GeoUtils.getGridSlope(sampleCoordinates, eleProvider);
                double elevation = eleProvider.getEle(center.lat, center.lon);
                OffRoadGrid offRoadGrid = new OffRoadGrid(j, i, slope, elevation, center, coordinates);
                // 混合路由：标记落在公路上的网格，并记录该处公路速度
                if (hybrid) {
                    RoadAccessProvider.RoadAccess roadAccess = roadAccessProvider.getRoad(center.lat, center.lon);
                    if (roadAccess != null && roadAccess.speedKmh() > 0) {
                        offRoadGrid.roadSpeedKmh = roadAccess.speedKmh();
                        offRoadGrid.roadLabel = roadAccess.roadLabel() == null ? "" : roadAccess.roadLabel();
                        offRoadGrid.roadName = roadAccess.roadName() == null ? "" : roadAccess.roadName();
                        offRoadGrid.roadEdgeId = roadAccess.edgeId();
                        offRoadGrid.roadAdjNode = roadAccess.adjNode();
                        offRoadGrid.roadSnapLat = roadAccess.snapLat();
                        offRoadGrid.roadSnapLon = roadAccess.snapLon();
                        maxSpeedKmh = Math.max(maxSpeedKmh, offRoadGrid.roadSpeedKmh);
                    }
                }
                // 越野避让：网格中心落在水体/建筑/铁路等要素上时不可通行；
                // 混合路由下已吸附公路的网格视为桥梁/涵洞跨越，不做避让，避免错误切断跨河道路
                if (obstacleProvider != null && offRoadGrid.roadSpeedKmh <= 0
                        && obstacleProvider.isBlocked(center.lat, center.lon)) {
                    offRoadGrid.blocked = true;
                }
                grids.add(offRoadGrid);

                // 直接复用网格对象作为起终点，确保其中心点与坡度信息正确
                if (this.startGrid == null && minX <= startPoint.lon && startPoint.lon <= maxX
                        && minY <= startPoint.lat && startPoint.lat <= maxY) {
                    this.startGrid = offRoadGrid;
                }
                if (this.endGrid == null && minX <= endPoint.lon && endPoint.lon <= maxX
                        && minY <= endPoint.lat && endPoint.lat <= maxY) {
                    this.endGrid = offRoadGrid;
                }
            }
        }

        return grids;
    }

    private OffRoadResponse calOffRoadPath(OffRoadResponse offRoadResponse, List<OffRoadGrid> grids,
                                           double cellSide, long deadline, long timeoutMs) {
        PointList roadPath = new PointList();
        JsonFeature barrierFeature = new JsonFeature();
        GeometryFactory geometryFactory = new GeometryFactory();
        List<Polygon> barrierPolygons = new ArrayList<>();

        if (grids.isEmpty()) {
            offRoadResponse.addOffRoadPoints(roadPath);
            return offRoadResponse;
        }

        // 建立二维索引，邻居查找降为 O(1)
        int rows = 0;
        int cols = 0;
        for (OffRoadGrid grid : grids) {
            rows = Math.max(rows, grid.y + 1);
            cols = Math.max(cols, grid.x + 1);
        }
        OffRoadGrid[][] cellIndex = new OffRoadGrid[rows][cols];
        for (OffRoadGrid grid : grids) {
            cellIndex[grid.y][grid.x] = grid;
            // 起终点所在网格始终放行，避免用户选点恰好落在陡坡或避让要素上导致无法起算
            if ((grid.slope > maxSlope || grid.blocked) && grid != startGrid && grid != endGrid) {
                // 坡度超限或落在避让要素上的网格视为障碍，直接关闭
                grid.isInCloseList = true;
                // 统计因地表覆盖禁行（水体/湿地/冰雪等）被排除的网格，供结果说明
                if (grid.blocked && obstacleProvider != null) {
                    offRoadResponse.addBlockedCell(
                            obstacleProvider.surfaceName(grid.center.lat, grid.center.lon));
                }
                Coordinate[] coordinates = new Coordinate[grid.coordinates.size()];
                for (int i = 0; i < grid.coordinates.size(); i++) {
                    GHPoint gridPoint = grid.coordinates.get(i);
                    coordinates[i] = new Coordinate(gridPoint.lon, gridPoint.lat);
                }
                barrierPolygons.add(geometryFactory.createPolygon(coordinates));
            } else {
                // 可通行网格初始化代价
                grid.g = Double.MAX_VALUE;
                grid.h = 0;
                grid.f = Double.MAX_VALUE;
                grid.isInOpenList = false;
                grid.isInCloseList = false;
                grid.parentId = null;
            }
        }
        // 相邻障碍网格合并为面，减少返回的多边形数量与前端绘制压力
        offRoadResponse.setBarrierCount(barrierPolygons.size());
        barrierFeature.setGeometry(mergeBarrierPolygons(geometryFactory, barrierPolygons));
        offRoadResponse.addBarrierFeature(barrierFeature);

        if (startGrid == null || endGrid == null) {
            // 起终点不在搜索范围内，返回空路径
            offRoadResponse.addOffRoadPoints(roadPath);
            return offRoadResponse;
        }

        // 标准 A*：按 f = g + h 升序扩展代价最小的节点
        PriorityQueue<OffRoadGrid> openList = new PriorityQueue<>(Comparator.comparingDouble(grid -> grid.f));
        startGrid.g = 0;
        startGrid.parentId = null;
        startGrid.h = heuristic(startGrid, endGrid);
        startGrid.f = startGrid.h;
        startGrid.isInOpenList = true;
        openList.add(startGrid);

        OffRoadGrid endNode = null;
        int expanded = 0;
        while (!openList.isEmpty()) {
            if ((expanded++ & 4095) == 0 && System.currentTimeMillis() > deadline) {
                throw new IllegalArgumentException("越野路径搜索超时（超过 " + timeoutMs
                        + " 毫秒），请增大网格间距或缩短起终点距离");
            }
            OffRoadGrid nodeCurrent = openList.poll();
            // 同一节点可能因代价更新被重复入队，已关闭的旧条目直接跳过
            if (nodeCurrent.isInCloseList) {
                continue;
            }
            nodeCurrent.isInCloseList = true;
            nodeCurrent.isInOpenList = false;
            if (nodeCurrent == endGrid) {
                endNode = nodeCurrent;
                break;
            }

            // 遍历八邻域
            for (int[] offset : NEIGHBOR_OFFSETS) {
                OffRoadGrid child = getNeighbor(cellIndex, nodeCurrent, offset[0], offset[1]);
                if (child == null || child.isInCloseList) {
                    continue;
                }
                boolean diagonal = offset[0] != 0 && offset[1] != 0;
                // 斜向移动时，两侧正交网格不能同时是障碍，避免从障碍拐角穿过
                if (diagonal) {
                    OffRoadGrid horizontal = getNeighbor(cellIndex, nodeCurrent, offset[0], 0);
                    OffRoadGrid vertical = getNeighbor(cellIndex, nodeCurrent, 0, offset[1]);
                    if (isBlocked(horizontal) && isBlocked(vertical)) {
                        continue;
                    }
                }
                double stepCost = hybrid
                        ? hybridStepCost(nodeCurrent, child)
                        : (diagonal ? Math.sqrt(2) : 1);
                double tentativeG = nodeCurrent.g + stepCost;
                // 找到更优路径才更新父节点与代价
                if (tentativeG < child.g) {
                    child.g = tentativeG;
                    child.h = heuristic(child, endGrid);
                    child.f = child.g + child.h;
                    child.parentId = nodeCurrent.id;
                    if (!child.isInOpenList) {
                        child.isInOpenList = true;
                    }
                    openList.add(child);
                }
            }
        }

        if (endNode == null) {
            // 起终点之间不存在可通行路径
            offRoadResponse.addOffRoadPoints(roadPath);
            return offRoadResponse;
        }

        // 回溯父节点，得到起点到终点的网格路径
        List<OffRoadGrid> pathGrids = new ArrayList<>();
        OffRoadGrid node = endNode;
        while (node != null) {
            pathGrids.add(node);
            node = parentOf(cellIndex, node);
        }
        Collections.reverse(pathGrids);

        // 纯越野：统计沿途经过的地表构成，用于说明哪些地表导致减速
        if (!hybrid && obstacleProvider != null && pathGrids.size() > 1) {
            List<OffRoadResponse.SurfaceSlice> pathSurfaces =
                    new ArrayList<>(collectSurfaces(pathGrids).values());
            pathSurfaces.sort(Comparator.comparingDouble(OffRoadResponse.SurfaceSlice::getDistance).reversed());
            offRoadResponse.setPathSurfaces(pathSurfaces);
        }

        if (hybrid) {
            // 混合路由的 g 即累计用时（秒），同时按公路/越野连续段切分明细
            double roadDistance = 0;
            double offRoadDistance = 0;
            double segmentDistance = 0;
            double segmentDuration = 0;
            double segmentAscent = 0;
            double segmentDescent = 0;
            double segmentMaxSlope = 0;
            String segmentRoadLabel = "";
            String segmentReason = "";
            // 该段经过的公路名称（去重、按出现顺序），越野段为空
            LinkedHashSet<String> segmentRoadNames = new LinkedHashSet<>();
            boolean segmentOnRoad = false;
            OffRoadGrid segmentStart = pathGrids.get(0);
            List<GHPoint> segmentPoints = new ArrayList<>();
            segmentPoints.add(new GHPoint(segmentStart.center.lat, segmentStart.center.lon));
            // 该段经过的网格，公路段据此拼接真实道路几何
            List<OffRoadGrid> segmentGrids = new ArrayList<>();
            segmentGrids.add(segmentStart);
            for (int i = 1; i < pathGrids.size(); i++) {
                OffRoadGrid previous = pathGrids.get(i - 1);
                OffRoadGrid current = pathGrids.get(i);
                double meters = GeoUtils.getDistance(
                        new GlobalCoordinates(previous.center.lat, previous.center.lon),
                        new GlobalCoordinates(current.center.lat, current.center.lon));
                boolean onRoad = previous.roadSpeedKmh > 0 && current.roadSpeedKmh > 0;
                if (i == 1) {
                    segmentOnRoad = onRoad;
                    if (!onRoad) {
                        segmentReason = "起点附近无可用道路，起步即越野";
                    } else {
                        segmentReason = "从公路起点出发，沿路网行驶";
                    }
                    segmentMaxSlope = current.slope;
                    if (onRoad) {
                        segmentRoadLabel = current.roadLabel;
                        if (!previous.roadName.isEmpty()) {
                            segmentRoadNames.add(previous.roadName);
                        }
                    }
                } else if (onRoad != segmentOnRoad) {
                    // 通行方式切换，结算上一段
                    OffRoadResponse.Segment settled = addHybridSegment(offRoadResponse, segmentOnRoad,
                            segmentDistance, segmentDuration, segmentAscent, segmentDescent, segmentMaxSlope,
                            segmentRoadLabel, segmentRoadNames,
                            segmentOnRoad ? roadGeometry(segmentGrids) : null,
                            segmentReason, segmentStart, previous, new ArrayList<>(segmentPoints));
                    if (!segmentOnRoad) {
                        settled.setSurfaces(sortSurfaces(collectSurfaces(segmentGrids)));
                    }
                    segmentDistance = 0;
                    segmentDuration = 0;
                    segmentAscent = 0;
                    segmentDescent = 0;
                    segmentMaxSlope = 0;
                    segmentRoadLabel = "";
                    segmentRoadNames = new LinkedHashSet<>();
                    segmentOnRoad = onRoad;
                    segmentStart = previous;
                    segmentPoints = new ArrayList<>();
                    segmentPoints.add(new GHPoint(previous.center.lat, previous.center.lon));
                    segmentGrids = new ArrayList<>();
                    segmentGrids.add(previous);
                    if (onRoad) {
                        segmentReason = "接入公路，转入路网行驶";
                        segmentRoadLabel = current.roadLabel;
                    } else {
                        segmentReason = hasRoadAhead(pathGrids, i)
                                ? "绕行公路代价更高，选择越野捷径"
                                : "公路在此中断，转入越野";
                    }
                }
                segmentPoints.add(new GHPoint(current.center.lat, current.center.lon));
                segmentGrids.add(current);
                segmentDistance += meters;
                segmentDuration += hybridStepCost(previous, current);
                double elevationDelta = current.elevation - previous.elevation;
                if (elevationDelta > 0) {
                    segmentAscent += elevationDelta;
                } else {
                    segmentDescent -= elevationDelta;
                }
                segmentMaxSlope = Math.max(segmentMaxSlope, current.slope);
                if (segmentRoadLabel.isEmpty() && !current.roadLabel.isEmpty()) {
                    segmentRoadLabel = current.roadLabel;
                }
                if (!current.roadName.isEmpty()) {
                    segmentRoadNames.add(current.roadName);
                }
                if (onRoad) {
                    roadDistance += meters;
                } else {
                    offRoadDistance += meters;
                }
            }
            if (pathGrids.size() > 1) {
                OffRoadGrid last = pathGrids.get(pathGrids.size() - 1);
                OffRoadResponse.Segment settled = addHybridSegment(offRoadResponse, segmentOnRoad,
                        segmentDistance, segmentDuration, segmentAscent, segmentDescent, segmentMaxSlope,
                        segmentRoadLabel, segmentRoadNames,
                        segmentOnRoad ? roadGeometry(segmentGrids) : null,
                        segmentReason, segmentStart, last, segmentPoints);
                if (!segmentOnRoad) {
                    settled.setSurfaces(sortSurfaces(collectSurfaces(segmentGrids)));
                }
            }
            offRoadResponse.setDurationSeconds(Math.round(endNode.g));
            offRoadResponse.setRoadDistance(roadDistance);
            offRoadResponse.setOffRoadDistance(offRoadDistance);
        }

        // 视线拉直 + 抽稀 + 平滑，消除格心连线的锯齿与绕路
        List<GHPoint> path = smoothPath(pathGrids, cellIndex, cellSide);
        for (GHPoint point : path) {
            roadPath.add(point);
        }
        offRoadResponse.addOffRoadPoints(roadPath);
        return offRoadResponse;
    }

    /**
     * 路径后处理：先用视线检测跳过可直连的中间格心，再用 Douglas-Peucker 抽稀，最后用 Chaikin 做一次平滑。
     */
    private List<GHPoint> smoothPath(List<OffRoadGrid> pathGrids, OffRoadGrid[][] cellIndex, double cellSide) {
        if (pathGrids.size() == 1) {
            // 起终点落在同一网格，直接连接两个真实坐标
            List<GHPoint> direct = new ArrayList<>();
            direct.add(startPoint);
            direct.add(endPoint);
            return direct;
        }

        List<GHPoint> points = new ArrayList<>();
        for (OffRoadGrid grid : lineOfSightSimplify(pathGrids, cellIndex)) {
            points.add(grid.center);
        }
        // 首尾替换为真实起终点，避免路径端点偏移到格心
        points.set(0, startPoint);
        points.set(points.size() - 1, endPoint);

        List<GHPoint> simplified = douglasPeucker(points, Math.max(cellSide * 0.5, 1));
        return chaikinOnce(simplified);
    }

    /**
     * 视线拉直：从当前锚点出发，尽量连接到最远的、与锚点之间无障碍的路径网格。
     */
    private List<OffRoadGrid> lineOfSightSimplify(List<OffRoadGrid> path, OffRoadGrid[][] cellIndex) {
        if (path.size() <= 2) {
            return path;
        }
        List<OffRoadGrid> result = new ArrayList<>();
        result.add(path.get(0));
        int anchor = 0;
        while (anchor < path.size() - 1) {
            int next = anchor + 1;
            for (int j = path.size() - 1; j > anchor + 1; j--) {
                if (hasLineOfSight(path.get(anchor), path.get(j), cellIndex)) {
                    next = j;
                    break;
                }
            }
            result.add(path.get(next));
            anchor = next;
        }
        return result;
    }

    /**
     * 判断两个网格之间的连线是否全部落在可通行网格内（每格采样 4 次）。
     */
    private boolean hasLineOfSight(OffRoadGrid from, OffRoadGrid to, OffRoadGrid[][] cellIndex) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) * 4);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(from.x + dx * t);
            int y = (int) Math.round(from.y + dy * t);
            if (y < 0 || y >= cellIndex.length || x < 0 || x >= cellIndex[0].length
                    || isBlocked(cellIndex[y][x])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Douglas-Peucker 抽稀，tolerance 为垂直距离阈值（米）。
     */
    private List<GHPoint> douglasPeucker(List<GHPoint> points, double tolerance) {
        int n = points.size();
        if (n < 3) {
            return points;
        }
        // 以平均纬度做等距投影，把经纬度换算成米，便于按米比较距离
        double meanLat = 0;
        for (GHPoint point : points) {
            meanLat += point.lat;
        }
        meanLat /= n;
        double lonScale = 111320.0 * Math.cos(Math.toRadians(meanLat));
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points.get(i).lon * lonScale;
            ys[i] = points.get(i).lat * 111320.0;
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int first = range[0];
            int last = range[1];
            double maxDistance = -1;
            int index = -1;
            for (int i = first + 1; i < last; i++) {
                double distance = perpendicularDistance(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last]);
                if (distance > maxDistance) {
                    maxDistance = distance;
                    index = i;
                }
            }
            if (index != -1 && maxDistance > tolerance) {
                keep[index] = true;
                stack.push(new int[]{first, index});
                stack.push(new int[]{index, last});
            }
        }
        List<GHPoint> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private double perpendicularDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return Math.hypot(px - ax, py - ay);
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /**
     * Chaikin 平滑一次，让折线拐角变得圆滑，同时保持首尾点不变。
     */
    private List<GHPoint> chaikinOnce(List<GHPoint> points) {
        if (points.size() < 3) {
            return points;
        }
        List<GHPoint> result = new ArrayList<>(points.size() * 2);
        result.add(points.get(0));
        for (int i = 0; i < points.size() - 1; i++) {
            GHPoint a = points.get(i);
            GHPoint b = points.get(i + 1);
            result.add(new GHPoint(a.lat * 0.75 + b.lat * 0.25, a.lon * 0.75 + b.lon * 0.25));
            result.add(new GHPoint(a.lat * 0.25 + b.lat * 0.75, a.lon * 0.25 + b.lon * 0.75));
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    /**
     * 障碍网格合并：相邻网格取并集，减少返回与渲染的多边形数量。
     */
    private Geometry mergeBarrierPolygons(GeometryFactory geometryFactory, List<Polygon> polygons) {
        if (polygons.isEmpty()) {
            return geometryFactory.createMultiPolygon(new Polygon[0]);
        }
        if (polygons.size() == 1) {
            return polygons.get(0);
        }
        try {
            return UnaryUnionOp.union(polygons);
        } catch (RuntimeException e) {
            // 合并失败时退化为原始网格面，保证接口仍然可用
            return geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
        }
    }

    /**
     * 八方向移动的 octile 距离，作为一致的启发函数，保证 A* 结果最优。
     * 混合路由按时间搜索，因此再换算为“以全图最大速度走完该距离所需秒数”。
     */
    private double heuristic(OffRoadGrid from, OffRoadGrid to) {
        int dx = Math.abs(from.x - to.x);
        int dy = Math.abs(from.y - to.y);
        double octile = (dx + dy) + (Math.sqrt(2) - 2) * Math.min(dx, dy);
        if (!hybrid) {
            return octile;
        }
        return octile * cellSideMeters / (maxSpeedKmh / 3.6);
    }

    /**
     * 混合路由单步代价（秒）：公路段按公路速度，越野段按坡度衰减后的越野速度，
     * 并对越野段施加惩罚，使路径在耗时接近时优先走公路。
     */
    private double hybridStepCost(OffRoadGrid from, OffRoadGrid to) {
        double meters = GeoUtils.getDistance(
                new GlobalCoordinates(from.center.lat, from.center.lon),
                new GlobalCoordinates(to.center.lat, to.center.lon));
        boolean onRoad = from.roadSpeedKmh > 0 && to.roadSpeedKmh > 0;
        double speedKmh = onRoad
                ? Math.max(5, Math.min(from.roadSpeedKmh, to.roadSpeedKmh) * militaryVehicleType.getRoadSpeedFactor())
                : offroadSpeed(from, to);
        double seconds = meters / (speedKmh / 3.6);
        return onRoad ? seconds : seconds * offroadPenalty;
    }

    /**
     * 记录一个公路/越野分段，并换算平均坡度（度）等展示用指标。
     */
    private OffRoadResponse.Segment addHybridSegment(OffRoadResponse response, boolean onRoad, double distance,
                                                     double duration, double ascent, double descent, double maxSlope,
                                                     String roadLabel, LinkedHashSet<String> roadNames,
                                                     List<GHPoint> directionPoints, String reason,
                                                     OffRoadGrid start, OffRoadGrid end, List<GHPoint> points) {
        double avgSlope = distance > 0 ? Math.toDegrees(Math.atan((ascent + descent) / distance)) : 0;
        // 公路段优先按真实道路几何计算方向，取不到几何时退回网格中心点
        List<GHPoint> pointsForDirection = directionPoints == null || directionPoints.size() < 2
                ? points : directionPoints;
        OffRoadResponse.Segment segment = new OffRoadResponse.Segment(onRoad, distance, Math.round(duration),
                start.center.lat, start.center.lon, end.center.lat, end.center.lon,
                avgSlope, maxSlope, ascent, descent, roadLabel,
                new ArrayList<>(roadNames), buildDirections(pointsForDirection), reason, points);
        response.addSegment(segment);
        return segment;
    }

    /**
     * 统计一组连续网格经过的地表构成，同类地表按里程累计。
     */
    private Map<String, OffRoadResponse.SurfaceSlice> collectSurfaces(List<OffRoadGrid> grids) {
        Map<String, OffRoadResponse.SurfaceSlice> surfaces = new LinkedHashMap<>();
        if (obstacleProvider == null || grids == null || grids.size() < 2) {
            return surfaces;
        }
        for (int i = 1; i < grids.size(); i++) {
            OffRoadGrid previous = grids.get(i - 1);
            OffRoadGrid current = grids.get(i);
            String label = obstacleProvider.surfaceName(current.center.lat, current.center.lon);
            if (label == null) {
                continue;
            }
            double meters = GeoUtils.getDistance(
                    new GlobalCoordinates(previous.center.lat, previous.center.lon),
                    new GlobalCoordinates(current.center.lat, current.center.lon));
            surfaces.computeIfAbsent(label, key -> new OffRoadResponse.SurfaceSlice(key,
                            obstacleProvider.speedFactor(current.center.lat, current.center.lon)))
                    .addDistance(meters);
        }
        return surfaces;
    }

    /**
     * 按里程从多到少排列地表构成，前端优先展示占比最大的地表。
     */
    private List<OffRoadResponse.SurfaceSlice> sortSurfaces(
            Map<String, OffRoadResponse.SurfaceSlice> surfaces) {
        List<OffRoadResponse.SurfaceSlice> list = new ArrayList<>(surfaces.values());
        list.sort(Comparator.comparingDouble(OffRoadResponse.SurfaceSlice::getDistance).reversed());
        return list;
    }

    /**
     * 按真实道路几何拼接该段的公路线形：逐格取吸附边的几何点串，统一朝行进方向后首尾相接。
     */
    private List<GHPoint> roadGeometry(List<OffRoadGrid> grids) {
        if (roadAccessProvider == null || grids == null || grids.isEmpty()) {
            return null;
        }
        List<GHPoint> geometry = new ArrayList<>();
        int lastEdgeId = -1;
        for (OffRoadGrid grid : grids) {
            // 同一路网边只取一次，避免相邻网格重复拼接同一段几何
            if (grid.roadEdgeId < 0 || grid.roadEdgeId == lastEdgeId) {
                continue;
            }
            List<GHPoint> edgePoints = roadAccessProvider.getEdgeGeometry(grid.roadEdgeId, grid.roadAdjNode);
            if (edgePoints == null || edgePoints.isEmpty()) {
                continue;
            }
            lastEdgeId = grid.roadEdgeId;
            List<GHPoint> ordered = new ArrayList<>(edgePoints);
            GHPoint reference = geometry.isEmpty()
                    ? new GHPoint(grid.roadSnapLat, grid.roadSnapLon)
                    : geometry.get(geometry.size() - 1);
            if (pointDistance(ordered.get(0), reference)
                    > pointDistance(ordered.get(ordered.size() - 1), reference)) {
                Collections.reverse(ordered);
            }
            for (GHPoint point : ordered) {
                if (geometry.isEmpty() || pointDistance(geometry.get(geometry.size() - 1), point) > 1) {
                    geometry.add(point);
                }
            }
        }
        return geometry.size() >= 2 ? geometry : null;
    }

    /**
     * 两个经纬度点之间的距离（米）。
     */
    private double pointDistance(GHPoint a, GHPoint b) {
        return GeoUtils.getDistance(
                new GlobalCoordinates(a.lat, a.lon),
                new GlobalCoordinates(b.lat, b.lon));
    }

    /**
     * 把分段折线按约 400 米一段归并成方向切片，避免网格锯齿产生过多碎方向。
     */
    private List<OffRoadResponse.DirectionSlice> buildDirections(List<GHPoint> points) {
        List<OffRoadResponse.DirectionSlice> slices = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return slices;
        }
        final double chunkMeters = 400;
        GHPoint chunkStart = points.get(0);
        GHPoint lastPoint = points.get(0);
        double chunkDistance = 0;
        for (int i = 1; i < points.size(); i++) {
            GHPoint current = points.get(i);
            chunkDistance += GeoUtils.getDistance(
                    new GlobalCoordinates(lastPoint.lat, lastPoint.lon),
                    new GlobalCoordinates(current.lat, current.lon));
            lastPoint = current;
            boolean isLast = i == points.size() - 1;
            if (!isLast && chunkDistance < chunkMeters) {
                continue;
            }
            if (chunkDistance <= 0) {
                continue;
            }
            String label = compassLabel(bearing(chunkStart.lat, chunkStart.lon, current.lat, current.lon));
            OffRoadResponse.DirectionSlice lastSlice = slices.isEmpty() ? null : slices.get(slices.size() - 1);
            if (lastSlice != null && lastSlice.getLabel().equals(label)) {
                lastSlice.addDistance(chunkDistance);
            } else {
                slices.add(new OffRoadResponse.DirectionSlice(label, chunkDistance));
            }
            chunkStart = current;
            chunkDistance = 0;
        }
        return slices;
    }

    /**
     * 起点指向终点的方位角（度，正北为 0，顺时针递增）。
     */
    private double bearing(double fromLat, double fromLon, double toLat, double toLon) {
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double deltaLon = Math.toRadians(toLon - fromLon);
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * 方位角转八方位中文描述。
     */
    private String compassLabel(double bearingDeg) {
        String[] labels = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return "向" + labels[(int) Math.round(bearingDeg / 45.0) % 8];
    }

    /**
     * 判断当前位置之后是否还会经过公路，用于解释为何离开公路。
     */
    private boolean hasRoadAhead(List<OffRoadGrid> pathGrids, int fromIndex) {
        for (int i = fromIndex; i < pathGrids.size(); i++) {
            if (pathGrids.get(i).roadSpeedKmh > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 越野速度：上坡与下坡分别衰减，不能一视同仁。
     * 上坡需要输出更大扭矩，坡度越大衰减越快；
     * 下坡主要是制动与安全控制，衰减幅度明显更小。具体系数由军车类型决定。
     */
    private double offroadSpeed(OffRoadGrid from, OffRoadGrid to) {
        double ratio = maxSlope <= 0 ? 0 : Math.min(1, Math.abs(to.slope) / maxSlope);
        double elevationDelta = to.elevation - from.elevation;
        double factor;
        if (elevationDelta > 0) {
            factor = Math.max(militaryVehicleType.getUphillMinFactor(),
                    1 - militaryVehicleType.getUphillDrop() * ratio);
        } else if (elevationDelta < 0) {
            factor = Math.max(militaryVehicleType.getDownhillMinFactor(),
                    1 - militaryVehicleType.getDownhillDrop() * ratio);
        } else {
            factor = 1;
        }
        // 地表覆盖折减：林地/灌丛/冰雪等按类别降低越野速度
        if (obstacleProvider != null) {
            factor *= Math.max(0.2, obstacleProvider.speedFactor(to.center.lat, to.center.lon));
        }
        return offroadSpeedKmh * factor;
    }

    /**
     * 获取当前网格指定偏移处的邻居，越界返回 null。
     */
    private OffRoadGrid getNeighbor(OffRoadGrid[][] cellIndex, OffRoadGrid current, int dx, int dy) {
        int x = current.x + dx;
        int y = current.y + dy;
        if (y < 0 || y >= cellIndex.length || x < 0 || x >= cellIndex[0].length) {
            return null;
        }
        return cellIndex[y][x];
    }

    /**
     * 判断网格是否不可通行（越界、坡度超限或落在避让要素上）。
     */
    private boolean isBlocked(OffRoadGrid grid) {
        return grid == null || grid.slope > maxSlope || grid.blocked;
    }

    /**
     * 根据 parentId（格式为 "x-y"）取回父网格。
     */
    private OffRoadGrid parentOf(OffRoadGrid[][] cellIndex, OffRoadGrid node) {
        if (node.parentId == null) {
            return null;
        }
        int separator = node.parentId.indexOf('-');
        int x = Integer.parseInt(node.parentId.substring(0, separator));
        int y = Integer.parseInt(node.parentId.substring(separator + 1));
        if (y < 0 || y >= cellIndex.length || x < 0 || x >= cellIndex[0].length) {
            return null;
        }
        return cellIndex[y][x];
    }
}
