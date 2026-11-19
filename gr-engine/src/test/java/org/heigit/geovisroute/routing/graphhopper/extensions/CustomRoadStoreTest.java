package org.heigit.geovisroute.routing.graphhopper.extensions;

import org.heigit.geovisroute.config.profile.CustomRoadNetworkProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomRoadStoreTest {

    @Test
    void shouldSaveAndListUpload(@TempDir Path tempDir) {
        CustomRoadStore store = new CustomRoadStore();
        String geoJson = "{\"type\":\"LineString\",\"coordinates\":[[120.1,23.1],[120.2,23.2]]}";

        Path saved = store.save(tempDir, List.of("driving-car"), "add", 25d, 20d,
                List.of("maxheight"), geoJson);

        assertThat(saved).exists();
        List<CustomRoadStore.StoredUpload> uploads = store.list(tempDir);
        assertThat(uploads).hasSize(1);
        CustomRoadStore.StoredUpload upload = uploads.get(0);
        assertThat(upload.getMode()).isEqualTo("add");
        assertThat(upload.getProfiles()).containsExactly("driving-car");
        assertThat(upload.getGeoJson()).isEqualTo(geoJson);
        assertThat(upload.getSnapRadius()).isEqualTo(25d);
        assertThat(upload.getMatchTolerance()).isEqualTo(20d);
        assertThat(upload.getOverlayTags()).containsExactly("maxheight");
    }

    @Test
    void shouldIgnoreBrokenRecordAndSortBySavedAt(@TempDir Path tempDir) throws Exception {
        CustomRoadStore store = new CustomRoadStore();
        store.save(tempDir, List.of("driving-car"), "add", 25d, 20d, List.of(), "{\"type\":\"LineString\"}");
        store.save(tempDir, List.of("driving-hgv"), "overlay", 25d, 20d, List.of(), "{\"type\":\"LineString\"}");
        Files.writeString(tempDir.resolve("dynamic-broken.json"), "{not-json");

        assertThat(store.list(tempDir)).hasSize(2);
        assertThat(store.list(tempDir)).extracting(CustomRoadStore.StoredUpload::getMode)
                .containsExactlyInAnyOrder("add", "overlay");
    }

    @Test
    void shouldFilterByProfileAndConvertToConfig(@TempDir Path tempDir) {
        CustomRoadStore store = new CustomRoadStore();
        store.save(tempDir, List.of("driving-car"), "overlay", 30d, 15d,
                List.of("maxweight"), "{\"type\":\"LineString\"}");

        CustomRoadStore.StoredUpload upload = store.list(tempDir).get(0);
        assertThat(CustomRoadStore.appliesTo(upload, "driving-car")).isTrue();
        assertThat(CustomRoadStore.appliesTo(upload, "driving-hgv")).isFalse();

        upload.getProfiles().clear();
        assertThat(CustomRoadStore.appliesTo(upload, "driving-hgv")).isTrue();

        CustomRoadNetworkProperties config = CustomRoadStore.toConfig(upload);
        assertThat(config.getMode()).isEqualTo("overlay");
        assertThat(config.getSnapRadius()).isEqualTo(30d);
        assertThat(config.getMatchTolerance()).isEqualTo(15d);
        assertThat(config.getOverlayTags()).containsExactly("maxweight");
    }
}
