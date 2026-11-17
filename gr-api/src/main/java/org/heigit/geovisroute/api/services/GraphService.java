package org.heigit.geovisroute.api.services;

import org.apache.log4j.Logger;
import org.heigit.geovisroute.util.AppInfo;
import org.heigit.geovisroute.config.EngineProperties;
import org.heigit.geovisroute.routing.RoutingProfile;
import org.heigit.geovisroute.routing.RoutingProfileManager;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GRGraphManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class GraphService {

    // 从 ors.engine.graph_management.enabled 获取此值
    private final Boolean enabled;

    private final EngineProperties engineProperties;

    private static final Logger LOGGER = Logger.getLogger(GraphService.class.getName());

    private final List<GRGraphManager> graphManagers = new ArrayList<>();

    private final AtomicBoolean graphActivationAttemptWasBlocked = new AtomicBoolean(false);
    private final AtomicBoolean isActivatingGraphs = new AtomicBoolean(true);

    @Autowired
    public GraphService(EngineProperties engineProperties, @Value("${ors.engine.graph_management.enabled:false}") Boolean enabled) {
        this.engineProperties = engineProperties;
        this.enabled = enabled;
    }

    public void addGraphManagerInstance(GRGraphManager orsGraphManager) {
        if (orsGraphManager.useGraphRepository()) {
            graphManagers.add(orsGraphManager);
        }
    }

    public void setIsActivatingGraphs(boolean value) {
        isActivatingGraphs.set(value);
    }

    @Async
    @Scheduled(cron = "${ors.engine.graph_management.download_schedule:0 0 0 31 2 *}")//默认值为 "never"
    public void checkForUpdatesInRepo() {

        if (Boolean.FALSE.equals(enabled)) {
            LOGGER.debug("图管理已禁用，跳过定时仓库检查...");
            return;
        }
        if (isActivatingGraphs.get()) {
            LOGGER.debug("GraphService 正在激活新图，跳过定时仓库检查...");
            return;
        }

        LOGGER.debug("开始定时仓库检查...");

        if (graphActivationAttemptWasBlocked.get()) {
            LOGGER.warn("跳过定时仓库检查，正在等待图激活...");
            return;
        }
        if (isUpdateLocked()) {
            LOGGER.warn("跳过定时仓库检查：发现文件 %s，请手动删除锁文件！".formatted(GRGraphManager.UPDATE_LOCKFILE_NAME));
            return;
        }

        for (GRGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.isBusy()) {
                LOGGER.info("[%s] 定时仓库检查：正在下载或解压".formatted(orsGraphManager.getQualifiedProfileName()));
            } else {
                LOGGER.info("[%s] 定时仓库检查：正在检查更新。".formatted(orsGraphManager.getQualifiedProfileName()));
                orsGraphManager.downloadAndExtractLatestGraphIfNecessary();
            }
        }

        LOGGER.debug("定时仓库检查完成");
    }

    @Async
    @Scheduled(cron = "${ors.engine.graph_management.activation_schedule:0 0 0 31 2 *}")//默认值为 "never"
    public void checkForDownloadedGraphsToActivate() {
        checkForDownloadedGraphsToActivate("Scheduled");
    }

    public void checkForDownloadedGraphsToActivate(String trigger) {
        if (Boolean.FALSE.equals(enabled)) {
            LOGGER.debug("图管理已禁用，跳过 %s 激活检查...".formatted(trigger.toLowerCase()));
            return;
        }
        if (isActivatingGraphs.get()) {
            LOGGER.debug("图激活正在进行中，跳过 %s 定时激活检查...".formatted(trigger.toLowerCase()));
            return;
        }

        LOGGER.debug("%s 图激活检查...".formatted(trigger));

        // 即使图激活已锁定：仍执行检查以启动 repeatedActivationAttempts。

        boolean graphActivationNeeded = false;
        boolean graphActivationAllowed = true;

        for (GRGraphManager orsGraphManager : graphManagers) {
            if (isGraphManagerBusyOrHasDownloadFile(orsGraphManager, trigger)) {
                graphActivationAllowed = false;
            }
            if (orsGraphManager.hasDownloadedExtractedGraph()) {
                logDownloadedExtractedGraphAvailable(orsGraphManager, trigger);
                graphActivationNeeded = true;
            }
        }

        if (!graphActivationNeeded) {
            LOGGER.info("%s 图激活检查完成：未发现已下载的图，无需激活任何图。".formatted(trigger));
            return;
        }
        if (!graphActivationAllowed) {
            LOGGER.info("%s 图激活检查完成：当前不允许激活，将每分钟重试...".formatted(trigger));
            graphActivationAttemptWasBlocked.set(true);
            return;
        }
        if (isActivationLocked()) {
            LOGGER.warn("%s 图激活检查完成：发现文件 %s，请手动删除锁文件！将每分钟重试...".formatted(
                    trigger,
                    GRGraphManager.ACTIVATION_LOCKFILE_NAME));
            graphActivationAttemptWasBlocked.set(true);
            return;
        }

        LOGGER.info("%s 图激活检查完成：开始执行图激活...".formatted(trigger));
        activateGraphs();
    }

    private boolean isGraphManagerBusyOrHasDownloadFile(GRGraphManager orsGraphManager, String trigger) {
        if (orsGraphManager.isBusy() || orsGraphManager.hasGraphDownloadFile()) {
            if (!graphActivationAttemptWasBlocked.get()) {
                LOGGER.info("[%s] %s 图激活检查：正在下载或解压".formatted(
                        orsGraphManager.getQualifiedProfileName(),
                        trigger
                ));
            }
            return true;
        }
        return false;
    }

    private void logDownloadedExtractedGraphAvailable(GRGraphManager orsGraphManager, String trigger) {
        if (!graphActivationAttemptWasBlocked.get()) {
            LOGGER.info("[%s] %s 图激活检查：已存在下载并解压完成的图".formatted(
                    orsGraphManager.getQualifiedProfileName(),
                    trigger));
        }
    }

    @Async
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void repeatedGraphActivationAttempt() {
        if (Boolean.FALSE.equals(enabled)) {
            LOGGER.trace("图管理已禁用，跳过重复尝试激活图...");
            return;
        }
        if (isActivatingGraphs.get()) {
            LOGGER.debug("图激活正在进行中，跳过重复尝试激活图...");
            return;
        }
        if (graphActivationAttemptWasBlocked.get()) {
            LOGGER.info("重复尝试激活图...");
            checkForDownloadedGraphsToActivate("Repeated");
        }
    }

    private boolean isUpdateLocked() {
        for (GRGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.hasUpdateLock()) {
                return true;
            }
        }
        return false;
    }

    private boolean isActivationLocked() {
        for (GRGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.hasActivationLock()) {
                return true;
            }
        }
        return false;
    }

    private void activateGraphs() {
        try {
            isActivatingGraphs.set(true);
            graphManagers.clear();
            RoutingProfileManager routingProfileManager = RoutingProfileManager.getInstance();
            routingProfileManager.initialize(engineProperties, AppInfo.GRAPH_VERSION);
            for (RoutingProfile profile : routingProfileManager.getUniqueProfiles()) {
                GRGraphManager orsGraphManager = profile.getGraphhopper().getOrsGraphManager();
                if (orsGraphManager != null && orsGraphManager.useGraphRepository()) {
                    LOGGER.debug("[%s] 正在将 profile %s（encoder 为 %s）的 orsGraphManager 添加到 GraphService".formatted(
                            orsGraphManager.getQualifiedProfileName(),
                            orsGraphManager.getQualifiedProfileName(),
                            profile.getProfileConfiguration().getEncoderName()));
                    addGraphManagerInstance(orsGraphManager);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("由于未预期的异常，无法激活图：" + e);
        } finally {
            isActivatingGraphs.set(false);
            graphActivationAttemptWasBlocked.set(false);
        }
    }
}
