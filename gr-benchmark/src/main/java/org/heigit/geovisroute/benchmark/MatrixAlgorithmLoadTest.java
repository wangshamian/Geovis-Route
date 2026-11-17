package org.heigit.geovisroute.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import org.heigit.geovisroute.benchmark.BenchmarkEnums.DirectionsModes;
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

public class MatrixAlgorithmLoadTest extends AbstractLoadTest {

    static {
        logger = LoggerFactory.getLogger(MatrixAlgorithmLoadTest.class);
    }

    public MatrixAlgorithmLoadTest() {
        super();
    }

    @Override
    protected void logConfigInfo() {
        logger.info("正在初始化 MatrixAlgorithmLoadTest：");
        logger.info("- 数据源文件：{}", config.getSourceFiles());
        logger.info("- 并发用户数：{}", config.getNumConcurrentUsers());
        logger.info("- 执行模式：{}", config.isParallelExecution() ? "parallel" : "sequential");
    }

    @Override
    protected void logTestTypeInfo() {
        logger.info("正在测试 matrix 服务");
    }

    @Override
    protected Stream<PopulationBuilder> createScenarios(boolean isParallel) {
        return config.getDirectionsModes().stream()
                .flatMap(mode -> config.getSourceFiles().stream()
                        .flatMap(sourceFile -> mode.getProfiles().stream()
                                .map(profile -> createScenarioWithInjection(sourceFile, isParallel, mode, profile))));
    }

    private PopulationBuilder createScenarioWithInjection(String sourceFile, boolean isParallel, DirectionsModes mode,
            String profile) {
        String scenarioName = formatScenarioName(mode, profile, isParallel);
        return createMatrixScenario(scenarioName, sourceFile, config, mode, profile)
                .injectOpen(atOnceUsers(config.getNumConcurrentUsers()));
    }

    private String formatScenarioName(DirectionsModes mode, String profile, boolean isParallel) {
        return String.format("%s - %s - %s", isParallel ? "Parallel" : "Sequential", mode, profile);
    }

    private static ScenarioBuilder createMatrixScenario(String name, String sourceFile, Config config,
            DirectionsModes mode, String profile) {

        try {
            List<Map<String, Object>> records = csv(sourceFile).readRecords();
            List<Map<String, Object>> targetRecords = SourceUtils.getRecordsByProfile(records, profile);

            AtomicInteger remainingRecords = new AtomicInteger(targetRecords.size());

            logger.info("场景 {}：正在处理 {} 个坐标，profile 为 {}", name, remainingRecords.get(),
                    profile);

            return scenario(name)
                    .feed(targetRecords.iterator(), 1)
                    .asLongAs(session -> remainingRecords.decrementAndGet() >= 0)
                    .on(exec(createRequest(name, config, mode, profile)));

        } catch (IllegalStateException e) {
            logger.error("构建场景时出错：", e);
            System.exit(1);
            return null;
        }
    }

    private static HttpRequestActionBuilder createRequest(String name, Config config, DirectionsModes mode,
            String profile) {
        return http(name)
                .post("/matrix/" + profile)
                .body(StringBody(session -> createRequestBody(session, config, mode)))
                .asJson()
                .check(status().is(200));
    }

    static String createRequestBody(Session session, Config config, DirectionsModes mode) {
        try {
            Map<String, Object> requestBody = new java.util.HashMap<>(Map.of(
                    "locations", createLocationsListFromArrays(session, config),
                    "sources", List.of(0),
                    "destinations", List.of(1)));
            requestBody.putAll(mode.getRequestParams());
            return objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new RequestBodyCreationException("Failed to create request body", e);
        }
    }

    static List<List<Double>> createLocationsListFromArrays(Session session, Config config) {
        List<List<Double>> locations = new ArrayList<>();
        try {
            Double startLon = Double.valueOf((String) session.getList(config.getFieldStartLon()).get(0));
            Double startLat = Double.valueOf((String) session.getList(config.getFieldStartLat()).get(0));
            locations.add(List.of(startLon, startLat));
            Double endLon = Double.valueOf((String) session.getList(config.getFieldEndLon()).get(0));
            Double endLat = Double.valueOf((String) session.getList(config.getFieldEndLat()).get(0));
            locations.add(List.of(endLon, endLat));
        } catch (NumberFormatException e) {
            String errorMessage = String.format(
                    "Failed to parse coordinate values in locations list at index %d. Original value could not be converted to double",
                    locations.size());
            throw new RequestBodyCreationException("Error processing coordinates: " + errorMessage, e);
        }
        return locations;
    }
}
