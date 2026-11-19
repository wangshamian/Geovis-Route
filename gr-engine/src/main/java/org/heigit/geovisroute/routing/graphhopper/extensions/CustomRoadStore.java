package org.heigit.geovisroute.routing.graphhopper.extensions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.heigit.geovisroute.config.profile.CustomRoadNetworkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 运行时动态加载的自定义路网持久化存储。
 * <p>
 * 每次通过接口加载的 GeoJSON 会连同目标 profile、模式与参数一起写入存储目录，
 * 建图（重新导入 OSM）时按 profile 过滤后自动重放，保证删除 graphs 目录后动态路网不丢失。
 */
public class CustomRoadStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomRoadStore.class);
    private static final String FILE_PREFIX = "dynamic-";
    private static final String FILE_SUFFIX = ".json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Path save(Path directory, List<String> profiles, String mode, Double snapRadius,
                     Double matchTolerance, List<String> overlayTags, String geoJson) {
        StoredUpload upload = new StoredUpload();
        upload.setProfiles(profiles == null ? List.of() : new ArrayList<>(profiles));
        upload.setMode(mode == null ? "add" : mode);
        upload.setSnapRadius(snapRadius);
        upload.setMatchTolerance(matchTolerance);
        upload.setOverlayTags(overlayTags == null ? List.of() : new ArrayList<>(overlayTags));
        upload.setGeoJson(geoJson);
        upload.setSavedAt(System.currentTimeMillis());
        String fileName = FILE_PREFIX + upload.getSavedAt() + "-"
                + UUID.randomUUID().toString().substring(0, 8) + FILE_SUFFIX;
        upload.setFileName(fileName);
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName);
            MAPPER.writeValue(target.toFile(), upload);
            LOGGER.info("已保存动态路网记录 {}（profiles={}, mode={}）", target.toAbsolutePath(),
                    upload.getProfiles(), upload.getMode());
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save dynamic custom road network to "
                    + directory.toAbsolutePath(), e);
        }
    }

    public List<StoredUpload> list(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<StoredUpload> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path path : stream) {
                try {
                    StoredUpload upload = MAPPER.readValue(path.toFile(), StoredUpload.class);
                    if (upload.getGeoJson() == null || upload.getGeoJson().isBlank()) {
                        LOGGER.warn("跳过空的动态路网记录 {}", path.toAbsolutePath());
                        continue;
                    }
                    upload.setFileName(path.getFileName().toString());
                    upload.setFileSize(Files.size(path));
                    result.add(upload);
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("跳过损坏的动态路网记录 {}: {}", path.toAbsolutePath(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list dynamic custom road network from "
                    + directory.toAbsolutePath(), e);
        }
        result.sort(Comparator.comparingLong(StoredUpload::getSavedAt));
        return result;
    }

    public static boolean appliesTo(StoredUpload upload, String profileName) {
        if (upload.getProfiles() == null || upload.getProfiles().isEmpty()) {
            return true;
        }
        for (String profile : upload.getProfiles()) {
            if (profile == null) {
                continue;
            }
            if ("all".equalsIgnoreCase(profile.trim()) || profile.trim().equalsIgnoreCase(profileName)) {
                return true;
            }
        }
        return false;
    }

    public static CustomRoadNetworkProperties toConfig(StoredUpload upload) {
        CustomRoadNetworkProperties config = new CustomRoadNetworkProperties();
        config.setMode(upload.getMode());
        if (upload.getSnapRadius() != null) {
            config.setSnapRadius(upload.getSnapRadius());
        }
        if (upload.getMatchTolerance() != null) {
            config.setMatchTolerance(upload.getMatchTolerance());
        }
        if (upload.getOverlayTags() != null && !upload.getOverlayTags().isEmpty()) {
            config.setOverlayTags(new ArrayList<>(upload.getOverlayTags()));
        }
        return config;
    }

    public static final class StoredUpload {
        private List<String> profiles = new ArrayList<>();
        private String mode = "add";
        private Double snapRadius;
        private Double matchTolerance;
        private List<String> overlayTags = new ArrayList<>();
        private String geoJson;
        private String fileName;
        private long savedAt;
        private long fileSize;

        public List<String> getProfiles() {
            return profiles;
        }

        public void setProfiles(List<String> profiles) {
            this.profiles = profiles;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Double getSnapRadius() {
            return snapRadius;
        }

        public void setSnapRadius(Double snapRadius) {
            this.snapRadius = snapRadius;
        }

        public Double getMatchTolerance() {
            return matchTolerance;
        }

        public void setMatchTolerance(Double matchTolerance) {
            this.matchTolerance = matchTolerance;
        }

        public List<String> getOverlayTags() {
            return overlayTags;
        }

        public void setOverlayTags(List<String> overlayTags) {
            this.overlayTags = overlayTags;
        }

        public String getGeoJson() {
            return geoJson;
        }

        public void setGeoJson(String geoJson) {
            this.geoJson = geoJson;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public long getSavedAt() {
            return savedAt;
        }

        public void setSavedAt(long savedAt) {
            this.savedAt = savedAt;
        }

        public long getFileSize() {
            return fileSize;
        }

        public void setFileSize(long fileSize) {
            this.fileSize = fileSize;
        }
    }
}
