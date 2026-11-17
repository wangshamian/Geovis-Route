package org.heigit.geovisroute.api;

import org.apache.commons.logging.Log;
import org.heigit.geovisroute.util.StringUtility;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public class GREnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String GR_CONFIG_LOCATION_ENV = "GR_CONFIG_LOCATION";
    public static final String GR_CONFIG_LOCATION_PROPERTY = "gr.config.location";
    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    private final Log log;

    public GREnvironmentPostProcessor(DeferredLogFactory logFactory) {
        log = logFactory.getLog(GREnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("test")) {
            log.info("未加载额外配置，当前处于 test profile。");
            return;
        }
        // 使用自定义配置 yml 文件的内容覆盖 application.yml 中的值。
        List<String> configLocations = new ArrayList<>();
        log.info("");
        log.info("开始查找配置。");
        if (!StringUtility.isNullOrEmpty(System.getProperty(GR_CONFIG_LOCATION_PROPERTY))) {
            configLocations.add(System.getProperty(GR_CONFIG_LOCATION_PROPERTY));
            log.info("配置文件已通过程序启动参数指定。");
        }
        if (configLocations.isEmpty() && !StringUtility.isNullOrEmpty(System.getenv(GR_CONFIG_LOCATION_ENV))) {
            String configPath = System.getenv(GR_CONFIG_LOCATION_ENV);
            File configFile = new File(configPath);
            // 检查文件是否存在并记录相应的消息
            if (configFile.exists()) {
                log.info("在以下路径找到 ORS 配置文件：" + configFile.getAbsolutePath());
                configLocations.add(configPath);
                log.info("配置文件已通过环境变量指定。");
            } else {
                // 仍然添加它以触发错误并停止 ORS
                configLocations.add(configPath);
                log.error("在以下路径未找到 ORS 配置文件：" + configPath);
            }
        }
        if (configLocations.isEmpty()) {
            String home = System.getProperty("user.home");
            configLocations.add("./gr-config.yml");
            configLocations.add(home + "/.config/openrouteservice/gr-config.yml");
            configLocations.add("/etc/openrouteservice/gr-config.yml");
            log.info("按默认位置继续查找配置文件。");
        }
        for (String path : configLocations) {
            try {
                List<PropertySource<?>> sources = this.loader.load("yml config", new FileSystemResource(path));
                if (!sources.isEmpty()) {
                    environment.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, sources.get(0));
                    System.setProperty(GR_CONFIG_LOCATION_PROPERTY, path);
                    log.info("已加载文件 '%s'。".formatted(path));
                    break;
                }
            } catch (IllegalStateException | IOException ignored) {
                log.debug("未找到配置文件 '%s'。".formatted(path));
            }
        }
        var relevantPrefixes = List.of(
                "ORS_",
                "ors.",
                "GR_",
                "gr.",
                "LOGGING_",
                "logging.",
                "SPRINGDOC_",
                "springdoc.",
                "SPRING_",
                "spring.",
                "SERVER_",
                "server.");
        var relevantENVs = environment.getSystemProperties().entrySet().stream()
                .filter(env -> relevantPrefixes.stream().anyMatch(env.getKey()::startsWith))
                .sorted(Entry.comparingByKey()).toList();
        if (!relevantENVs.isEmpty()) {
            log.info("");
            log.info("检测到覆盖 openrouteservice 配置参数的环境变量：");
            relevantENVs.forEach(env -> log.info("%s=%s".formatted(env.getKey(), env.getValue())));
        }
        log.info("配置查找结束。");
        log.info("");
    }
}
