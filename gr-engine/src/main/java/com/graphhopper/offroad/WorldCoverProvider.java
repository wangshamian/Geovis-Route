package com.graphhopper.offroad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 基于 ESA WorldCover 10 m 类别栅格的避让提供者。
 * <p>
 * 输入为离线裁剪后的类别 PNG（像素值即类别代码）与元数据 JSON（bbox、宽高）。
 * 冰雪、水体、湿地、红树林禁行；建成区、林地、灌丛等按类别折减越野速度。
 */
public class WorldCoverProvider implements ObstacleProvider {

    /**
     * 不可通行类别：冰雪、水体、湿地、红树林。
     */
    private static final Set<Integer> BLOCKED_CLASSES = Set.of(70, 80, 90, 95);
    /**
     * 可通行但需要减速的类别及其速度系数。
     */
    private static final Map<Integer, Double> SPEED_FACTORS = Map.of(
            10, 0.60,  // 林地
            20, 0.75,  // 灌丛
            30, 1.00,  // 草地
            40, 0.90,  // 耕地
            50, 0.60,  // 建成区（仍可缓慢通行）
            60, 0.80,  // 裸地
            100, 0.70  // 苔藓
    );
    /**
     * 类别代码到中文名称的映射，用于路径结果中的地表说明。
     */
    private static final Map<Integer, String> CLASS_NAMES = Map.ofEntries(
            Map.entry(10, "林地"),
            Map.entry(20, "灌丛"),
            Map.entry(30, "草地"),
            Map.entry(40, "耕地"),
            Map.entry(50, "建成区"),
            Map.entry(60, "裸地"),
            Map.entry(70, "冰雪"),
            Map.entry(80, "水体"),
            Map.entry(90, "湿地"),
            Map.entry(95, "红树林"),
            Map.entry(100, "苔藓")
    );

    private final BufferedImage image;
    private final int width;
    private final int height;
    private final double minLon;
    private final double minLat;
    private final double maxLon;
    private final double maxLat;

    public WorldCoverProvider(Path imageFile, Path metaFile) throws IOException {
        this.image = ImageIO.read(imageFile.toFile());
        if (image == null) {
            throw new IOException("无法解析地表覆盖栅格：" + imageFile);
        }
        this.width = image.getWidth();
        this.height = image.getHeight();

        JsonNode meta = new ObjectMapper().readTree(metaFile.toFile());
        JsonNode bbox = meta.path("bbox");
        if (!bbox.isArray() || bbox.size() < 4) {
            throw new IOException("地表覆盖元数据缺少 bbox：" + metaFile);
        }
        this.minLon = bbox.get(0).asDouble();
        this.minLat = bbox.get(1).asDouble();
        this.maxLon = bbox.get(2).asDouble();
        this.maxLat = bbox.get(3).asDouble();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public boolean isBlocked(double lat, double lon) {
        return BLOCKED_CLASSES.contains(classCode(lat, lon));
    }

    @Override
    public double speedFactor(double lat, double lon) {
        return SPEED_FACTORS.getOrDefault(classCode(lat, lon), 1.0);
    }

    @Override
    public String surfaceName(double lat, double lon) {
        return CLASS_NAMES.get(classCode(lat, lon));
    }

    /**
     * 按经纬度取最近的栅格类别；范围外返回 0（未知，按可通行处理）。
     */
    private int classCode(double lat, double lon) {
        if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) {
            return 0;
        }
        int x = (int) ((lon - minLon) / (maxLon - minLon) * width);
        int y = (int) ((maxLat - lat) / (maxLat - minLat) * height);
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        return image.getRaster().getSample(x, y, 0);
    }
}
