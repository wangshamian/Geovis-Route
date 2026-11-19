package org.heigit.geovisroute.api.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.heigit.geovisroute.config.profile.CustomRoadNetworkProperties;
import org.heigit.geovisroute.routing.GraphUpdateLock;
import org.heigit.geovisroute.routing.RoutingProfile;
import org.heigit.geovisroute.routing.RoutingProfileManager;
import org.heigit.geovisroute.routing.graphhopper.extensions.CustomRoadStore;
import org.heigit.geovisroute.routing.graphhopper.extensions.GRGraphHopper;
import org.heigit.geovisroute.routing.graphhopper.extensions.RuntimeRoadNetworkUpdater;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 运行时自定义路网管理接口，无需重启即可把 GeoJSON 加载进已构建的路网。
 */
@RestController
@Tag(name = "Custom Roads Service", description = "运行时动态加载自定义路网（无需重启）")
@RequestMapping
public class CustomRoadsAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomRoadsAPI.class);

    @Operation(summary = "运行时加载自定义路网",
            description = "支持 add（注入新道路）与 overlay（覆盖已有道路限制属性）两种模式；更新期间其他图相关请求返回 503。")
    @PostMapping(value = "/custom-roads", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> updateRoadNetwork(
            @RequestParam(value = "profiles", required = false) List<String> profiles,
            @RequestParam(value = "mode", defaultValue = "add") String mode,
            @RequestParam(value = "snap_radius", required = false) Double snapRadius,
            @RequestParam(value = "match_tolerance", required = false) Double matchTolerance,
            @RequestParam(value = "overlay_tags", required = false) List<String> overlayTags,
            @RequestParam(value = "persist", defaultValue = "true") boolean persist,
            @RequestBody(required = false) String geoJson) {

        if (geoJson == null || geoJson.isBlank()) {
            return error(400, "请求体不能为空，需提交 GeoJSON");
        }
        String normalizedMode = mode == null ? "add" : mode.trim().toLowerCase(Locale.ROOT);
        if (!"add".equals(normalizedMode) && !"overlay".equals(normalizedMode)) {
            return error(400, "mode 只支持 add 或 overlay");
        }
        try {
            new JSONObject(geoJson);
        } catch (RuntimeException e) {
            return error(400, "GeoJSON 解析失败: " + e.getMessage());
        }

        List<RoutingProfile> targets;
        try {
            targets = resolveProfiles(profiles);
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
        if (targets.isEmpty()) {
            return error(400, "没有可用的 routing profile");
        }

        try {
            if (!GraphUpdateLock.tryWriteLock(10, TimeUnit.SECONDS)) {
                return error(409, "已有路网更新任务正在执行，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(409, "路网更新等待被中断，请稍后重试");
        }
        try {
            List<Map<String, Object>> details = new ArrayList<>();
            List<String> updatedProfiles = new ArrayList<>();
            int totalNodes = 0;
            int totalEdges = 0;
            int totalMatchedEdges = 0;
            long indexRebuildMs = 0;
            long chRebuildMs = 0;
            long totalMs = 0;

            for (RoutingProfile routingProfile : targets) {
                GRGraphHopper graphHopper = routingProfile.getGraphhopper();
                String profileName = routingProfile.getProfileConfiguration().getProfileName();
                CustomRoadNetworkProperties config = buildConfig(graphHopper, normalizedMode,
                        snapRadius, matchTolerance, overlayTags);

                RuntimeRoadNetworkUpdater.UpdateResult result =
                        new RuntimeRoadNetworkUpdater(graphHopper).apply(geoJson, config);

                updatedProfiles.add(profileName);
                totalNodes += result.getNodesAdded();
                totalEdges += result.getEdgesAdded();
                totalMatchedEdges += result.getMatchedEdges();
                indexRebuildMs += result.getIndexRebuildMs();
                chRebuildMs += result.getChRebuildMs();
                totalMs += result.getTotalMs();

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("profile", profileName);
                detail.put("nodes_added", result.getNodesAdded());
                detail.put("edges_added", result.getEdgesAdded());
                detail.put("total_nodes", result.getTotalNodes());
                detail.put("total_edges", result.getTotalEdges());
                detail.put("matched_features", result.getMatchedFeatures());
                detail.put("matched_edges", result.getMatchedEdges());
                detail.put("skipped", result.getSkipped());
                detail.put("import_ms", result.getImportMs());
                detail.put("index_rebuild_ms", result.getIndexRebuildMs());
                detail.put("ch_rebuild_ms", result.getChRebuildMs());
                detail.put("total_ms", result.getTotalMs());
                details.add(detail);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", normalizedMode);
            body.put("profiles", updatedProfiles);
            body.put("nodes_added", totalNodes);
            body.put("edges_added", totalEdges);
            body.put("matched_edges", totalMatchedEdges);
            body.put("index_rebuild_ms", indexRebuildMs);
            body.put("ch_rebuild_ms", chRebuildMs);
            body.put("total_ms", totalMs);
            if (persist) {
                Path saved = new CustomRoadStore().save(
                        targets.get(0).getGraphhopper().getCustomRoadStorageDir(),
                        updatedProfiles, normalizedMode, snapRadius, matchTolerance, overlayTags, geoJson);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("file_name", saved.getFileName().toString());
                record.put("path", saved.toAbsolutePath().toString());
                body.put("stored_record", record);
            }
            body.put("details", details);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("运行时更新路网参数错误: {}", e.getMessage());
            return error(400, e.getMessage());
        } catch (UnsupportedOperationException e) {
            LOGGER.warn("运行时更新路网不被支持: {}", e.getMessage());
            return error(409, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("运行时更新路网失败", e);
            return error(500, "路网更新失败: " + e.getMessage());
        } finally {
            GraphUpdateLock.unlockWrite();
        }
    }

    @Operation(summary = "查询已保存的动态路网记录")
    @GetMapping(value = "/custom-roads", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> listStoredRoadNetworks() {
        List<RoutingProfile> profiles = RoutingProfileManager.getInstance().getUniqueProfiles();
        List<Map<String, Object>> records = new ArrayList<>();
        if (!profiles.isEmpty()) {
            Path storageDir = profiles.get(0).getGraphhopper().getCustomRoadStorageDir();
            for (CustomRoadStore.StoredUpload upload : new CustomRoadStore().list(storageDir)) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("file_name", upload.getFileName());
                record.put("profiles", upload.getProfiles());
                record.put("mode", upload.getMode());
                record.put("snap_radius", upload.getSnapRadius());
                record.put("match_tolerance", upload.getMatchTolerance());
                record.put("overlay_tags", upload.getOverlayTags());
                record.put("saved_at", upload.getSavedAt());
                record.put("file_size", upload.getFileSize());
                records.add(record);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", records);
        return ResponseEntity.ok(body);
    }

    private List<RoutingProfile> resolveProfiles(List<String> requested) {
        RoutingProfileManager manager = RoutingProfileManager.getInstance();
        Set<String> names = new LinkedHashSet<>();
        if (requested != null) {
            for (String value : requested) {
                if (value == null) {
                    continue;
                }
                for (String part : value.split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        if (names.isEmpty() || names.stream().anyMatch("all"::equalsIgnoreCase)) {
            return manager.getUniqueProfiles();
        }
        List<RoutingProfile> result = new ArrayList<>();
        for (String name : names) {
            RoutingProfile routingProfile = manager.getRoutingProfile(name);
            if (routingProfile == null) {
                throw new IllegalArgumentException("未知或未加载的 profile: " + name);
            }
            result.add(routingProfile);
        }
        return result;
    }

    private CustomRoadNetworkProperties buildConfig(GRGraphHopper graphHopper, String mode,
                                                    Double snapRadius, Double matchTolerance,
                                                    List<String> overlayTags) {
        CustomRoadNetworkProperties config = new CustomRoadNetworkProperties();
        CustomRoadNetworkProperties source = graphHopper.getProfileProperties() != null
                && graphHopper.getProfileProperties().getBuild() != null
                ? graphHopper.getProfileProperties().getBuild().getCustomRoads()
                : null;
        if (source != null) {
            config.setFile(source.getFile());
            config.setSnapRadius(source.getSnapRadius());
            config.setMatchTolerance(source.getMatchTolerance());
            if (source.getOverlayTags() != null) {
                config.setOverlayTags(new ArrayList<>(source.getOverlayTags()));
            }
            if (source.getTags() != null) {
                config.setTags(new LinkedHashMap<>(source.getTags()));
            }
            config.setStorageDir(source.getStorageDir());
        }
        config.setMode(mode);
        if (snapRadius != null) {
            config.setSnapRadius(snapRadius);
        }
        if (matchTolerance != null) {
            config.setMatchTolerance(matchTolerance);
        }
        if (overlayTags != null && !overlayTags.isEmpty()) {
            config.setOverlayTags(overlayTags.stream().filter(Objects::nonNull)
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        return config;
    }

    private ResponseEntity<Map<String, Object>> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
