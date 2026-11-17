package org.heigit.geovisroute.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import org.heigit.geovisroute.benchmark.BenchmarkEnums.RangeType;
import org.heigit.geovisroute.benchmark.exceptions.RequestBodyCreationException;
import org.heigit.geovisroute.util.SourceUtils;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static org.heigit.geovisroute.util.NameUtils.getFileNameWithoutExtension;

public class IsochronesRangeLoadTest extends AbstractLoadTest {

    static {
        logger = LoggerFactory.getLogger(IsochronesRangeLoadTest.class);
    }

    public IsochronesRangeLoadTest() {
        super();
    }

    @Override
    protected void logConfigInfo() {
        logger.info("正在使用以下配置初始化 IsochronesRangeLoadTest：");
        logger.info("- 数据源文件：{}", config.getSourceFiles());
        logger.info("- 目标 profile：{}", config.getTargetProfile());
        logger.info("- 并发用户数：{}", config.getNumConcurrentUsers());
        logger.info("- 查询规模：{}", config.getQuerySizes());
        logger.info("- 范围值：{}", config.getRanges());
        logger.info("- 测试单位：{}", config.getTestUnit());
        logger.info("- 基础 URL：{}", config.getBaseUrl());
        logger.info("- 执行模式：{}", config.isParallelExecution() ? "parallel" : "sequential");
    }

    @Override
    protected void logTestTypeInfo() {
        logger.info("正在测试 {} 类型的等值线服务", config.getTestUnit());
    }

    @Override
    protected Stream<PopulationBuilder> createScenarios(boolean isParallel) {
        return createScenariosForTestUnit().stream()
                .flatMap(rangeType -> config.getSourceFiles().stream()
                        .flatMap(sourceFile -> config.getQuerySizes().stream()
                                .map(querySize -> createScenarioWithInjection(sourceFile, querySize, isParallel,
                                        rangeType))));
    }

    private List<RangeType> createScenariosForTestUnit() {
        return switch (config.getTestUnit()) {
            case DISTANCE -> List.of(RangeType.DISTANCE);
            case TIME -> List.of(RangeType.TIME);
        };
    }

    private PopulationBuilder createScenarioWithInjection(String sourceFile, int querySize, boolean isParallel,
            RangeType rangeType) {
        String scenarioName = formatScenarioName(sourceFile, querySize);
        return createIsochroneScenario(scenarioName, querySize, sourceFile, config, rangeType, isParallel)
                .injectOpen(atOnceUsers(config.getNumConcurrentUsers()));
    }

    private String formatScenarioName(String sourceFile, int querySize) {
        String fileName = getFileNameWithoutExtension(sourceFile);
        return String.format("Locations (%d) | %s", querySize, fileName);
    }

    private static ScenarioBuilder createIsochroneScenario(String name, int locationCount, String sourceFile,
                                                           Config config, RangeType rangeType, boolean isParallel) {

        logger.info(
                "正在创建场景：name={}, locationCount={}, sourceFile={}, profile={}, rangeType={}, isParallel={}",
                name, locationCount, sourceFile, config.getTargetProfile(), rangeType, isParallel);

        String parallelOrSequential = isParallel ? "parallel" : "sequential";
        String groupName = String.format("Isochrones %s %s - %s - Users %s - Ranges %s",
                parallelOrSequential, rangeType.getValue(), getFileNameWithoutExtension(sourceFile),
                        config.getNumConcurrentUsers(), config.getRanges());

        // 获取目标配置方案的记录；若没有 profile 列则获取所有记录

        try {
            List<Map<String, Object>> records = csv(sourceFile).readRecords();
            List<Map<String, Object>> targetRecords = SourceUtils.getRecordsByProfile(records,
                    config.getTargetProfile());

            AtomicInteger remainingRecords = new AtomicInteger(targetRecords.size());

            logger.info("正在处理 {} 个坐标，profile 为 {}", remainingRecords.get(),
                    config.getTargetProfile());

            return scenario(name)
                    .group(groupName)
                    .on(
                            feed(targetRecords.iterator(), locationCount)
                                    .exec(createIsochroneRequest(name, locationCount, config, rangeType)));
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            return scenario(name);
        }
    }

    private static HttpRequestActionBuilder createIsochroneRequest(String name, int locationCount, Config config,
            RangeType rangeType) {
        return http(name)
                .post("/isochrones/" + config.getTargetProfile())
                .body(StringBody(session -> createRequestBody(session, locationCount, config, rangeType)))
                .asJson()
                .check(status().is(200));
    }

    static String createRequestBody(Session session, int locationCount, Config config, RangeType rangeType) {
        try {
            List<List<Double>> locations = createLocationsListFromArrays(session, locationCount, config);

            Map<String, Object> requestBody = Map.of(
                    "locations", locations,
                            "range_type", rangeType.getValue(),
                    "range", config.getRanges());

            String body = objectMapper.writeValueAsString(requestBody);
            logger.debug("已创建请求体：{}", body);
            return body;
        } catch (JsonProcessingException e) {
            throw new RequestBodyCreationException("Failed to create request body", e);
        }
    }

    private static void processCoordinates(List<List<Double>> locations, List<?> lons, List<?> lats, int size) {
        for (int i = 0; i < size; i++) {
            Object lon = lons.get(i);
            Object lat = lats.get(i);

            if (lon == null || lat == null) {
                logger.warn("索引 {} 处的坐标为 null：lon={}, lat={}", i, lon, lat);
                continue;
            }

            try {
                double lonValue = Double.parseDouble(lon.toString());
                double latValue = Double.parseDouble(lat.toString());
                locations.add(List.of(lonValue, latValue));
            } catch (NumberFormatException e) {
                throw new RequestBodyCreationException("Failed to parse coordinate values", e);
            }
        }
    }

    static List<List<Double>> createLocationsListFromArrays(Session session, int locationCount, Config config) {
        logger.debug("正在按批次大小 {} 创建位置列表", locationCount);
        List<List<Double>> locations = new ArrayList<>();

        try {
            logger.debug("正在读取以下字段的会话数据：lon={}, lat={}", config.getFieldLon(), config.getFieldLat());
            logger.debug("会话 {}", session);
            List<?> lons = session.get(config.getFieldLon());
            List<?> lats = session.get(config.getFieldLat());

            if (lons == null || lats == null) {
                logger.error("会话数据为 null，lon: {}，lat: {}", lons, lats);
                return locations;
            }

            logger.debug("会话数据：lon 数量为 {}，lat 数量为 {}", lons.size(), lats.size());

            int size = Math.min(Math.min(locationCount, lons.size()), lats.size());
            logger.debug("正在处理 {} 个坐标", size);

            processCoordinates(locations, lons, lats, size);

            logger.debug("已创建包含 {} 个坐标对的位置列表", locations.size());
            if (!locations.isEmpty()) {
                logger.debug("示例坐标：{}", locations.get(0));
            }

        } catch (NumberFormatException e) {
            throw new RequestBodyCreationException("Error processing coordinates", e);
        }

        return locations;
    }
}
