package org.heigit.geovisroute.routing.graphhopper.extensions.manage.remote;

import org.apache.log4j.Logger;
import org.heigit.geovisroute.exceptions.GRGraphFileManagerException;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphManagementRuntimeProperties;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.PersistedGraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.local.GRGraphFileManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static com.google.common.base.Strings.isNullOrEmpty;

public class FileSystemGraphRepoClient extends AbstractGraphRepoClient implements GRGraphRepoClient {

    private static final Logger LOGGER = Logger.getLogger(FileSystemGraphRepoClient.class.getName());
    private final String graphsRepoPath;
    private final String graphsRepoName;
    private final String graphsProfileGroup;
    private final String graphsRepoCoverage;
    private final String graphsRepoGraphVersion;
    private final GRGraphFileManager orsGraphFileManager;
    private final GRGraphRepoStrategy orsGraphRepoStrategy;

    public FileSystemGraphRepoClient(GraphManagementRuntimeProperties graphManagementRuntimeProperties, GRGraphRepoStrategy orsGraphRepoStrategy, GRGraphFileManager orsGraphFileManager) {
        this.graphsRepoPath = graphManagementRuntimeProperties.getDerivedRepoPath().toAbsolutePath().toString();
        this.graphsRepoName = graphManagementRuntimeProperties.getRepoName();
        this.graphsProfileGroup = graphManagementRuntimeProperties.getRepoProfileGroup();
        this.graphsRepoCoverage = graphManagementRuntimeProperties.getRepoCoverage();
        this.graphsRepoGraphVersion = graphManagementRuntimeProperties.getGraphVersion();
        this.orsGraphRepoStrategy = orsGraphRepoStrategy;
        this.orsGraphFileManager = orsGraphFileManager;
    }

    String getProfileDescriptiveName() {
        return orsGraphFileManager.getProfileDescriptiveName();
    }

    @Override
    public void downloadGraphIfNecessary() {
        if (isNullOrEmpty(graphsRepoPath) || isNullOrEmpty(graphsRepoName) || isNullOrEmpty(graphsRepoCoverage) || isNullOrEmpty(graphsRepoGraphVersion)) {
            LOGGER.debug("[%s] GRGraphManager 未配置 - 跳过检查".formatted(getProfileDescriptiveName()));
            return;
        }
        if (orsGraphFileManager.isBusy()) {
            LOGGER.debug("[%s] GRGraphManager 正忙 - 跳过检查".formatted(getProfileDescriptiveName()));
            return;
        }

        LOGGER.debug("[%s] 正在检查远程仓库中是否有可用的图更新...".formatted(getProfileDescriptiveName()));
        try {
            PersistedGraphBuildInfo previouslyDownloadedGraphBuildInfo = orsGraphFileManager.getDownloadedGraphBuildInfo();
            File downloadedCompressedGraphFile = orsGraphFileManager.getDownloadedCompressedGraphFile();
            GraphBuildInfo activeGraphBuildInfo = orsGraphFileManager.getActiveGraphBuildInfo();
            GraphBuildInfo downloadedExtractedGraphBuildInfo = orsGraphFileManager.getDownloadedExtractedGraphBuildInfo();
            GraphBuildInfo newlyDownloadedGraphBuildInfo = downloadLatestGraphBuildInfoFromRepository();
            LOGGER.trace(("[%s] 正在比较日期，若第一项晚于其他项则下载:%n" +
                    "                         repo=%s%n" +
                    "              activeGraphBuildInfo=%s%n" +
                    " downloadedExtractedGraphBuildInfo=%s%n" +
                    "previouslyDownloadedGraphBuildInfo=%s").formatted(getProfileDescriptiveName(),
                    getDateOrEpocStart(newlyDownloadedGraphBuildInfo),
                    getDateOrEpocStart(activeGraphBuildInfo),
                    getDateOrEpocStart(downloadedExtractedGraphBuildInfo),
                    getDateOrEpocStart(downloadedCompressedGraphFile, previouslyDownloadedGraphBuildInfo)));

            if (!shouldDownloadGraph(
                    getDateOrEpocStart(newlyDownloadedGraphBuildInfo),
                    getDateOrEpocStart(activeGraphBuildInfo),
                    getDateOrEpocStart(downloadedExtractedGraphBuildInfo),
                    getDateOrEpocStart(downloadedCompressedGraphFile, previouslyDownloadedGraphBuildInfo))) {
                LOGGER.info("[%s] 仓库中未找到更新的图数据。".formatted(getProfileDescriptiveName()));
                return;
            }

            Path latestCompressedGraphInRepoPath = Path.of(graphsRepoPath, graphsRepoName, graphsProfileGroup, graphsRepoCoverage, graphsRepoGraphVersion, orsGraphRepoStrategy.getRepoCompressedGraphFileName());
            long start = System.currentTimeMillis();
            downloadFile(latestCompressedGraphInRepoPath, downloadedCompressedGraphFile);

            long end = System.currentTimeMillis();
            if (downloadedCompressedGraphFile.exists()) {
                LOGGER.info("[%s] 压缩图文件下载完成，耗时 %d ms".formatted(getProfileDescriptiveName(), end - start));
            } else {
                LOGGER.error("[%s] 压缩图文件的下载路径无效: %s".formatted(getProfileDescriptiveName(), latestCompressedGraphInRepoPath));
            }
        } catch (Exception e) {
            LOGGER.error("[%s] 在检查图下载或下载图数据时捕获到异常:".formatted(getProfileDescriptiveName()), e);
        }
    }

    GraphBuildInfo downloadLatestGraphBuildInfoFromRepository() throws GRGraphFileManagerException {
        GraphBuildInfo latestGraphBuildInfoInRepo = new GraphBuildInfo();
        LOGGER.debug("[%s] 正在检查远程仓库中最新的 graphBuildInfo...".formatted(getProfileDescriptiveName()));

        Path latestGraphBuildInfoInRepoPath = Path.of(graphsRepoPath, graphsRepoName, graphsProfileGroup, graphsRepoCoverage, graphsRepoGraphVersion, orsGraphRepoStrategy.getRepoGraphBuildInfoFileName());
        if (!latestGraphBuildInfoInRepoPath.toFile().exists()) {
            LOGGER.info("[%s] 远程仓库中未找到 graphBuildInfo: %s".formatted(getProfileDescriptiveName(), latestGraphBuildInfoInRepoPath.toFile().getAbsolutePath()));
            return latestGraphBuildInfoInRepo;
        }

        File downloadedGraphBuildInfoFile = orsGraphFileManager.getDownloadedGraphBuildInfoFile();
        downloadFile(latestGraphBuildInfoInRepoPath, downloadedGraphBuildInfoFile);

        if (downloadedGraphBuildInfoFile.exists()) {
            Path latestCompressedGraphInRepoPath = Path.of(graphsRepoPath, graphsRepoName, graphsProfileGroup, graphsRepoCoverage, graphsRepoGraphVersion, orsGraphRepoStrategy.getRepoCompressedGraphFileName());
            URI uri = latestCompressedGraphInRepoPath.toUri();
            latestGraphBuildInfoInRepo.setRemoteUri(uri);

            PersistedGraphBuildInfo persistedGraphBuildInfo = orsGraphFileManager.readOrsGraphBuildInfo(downloadedGraphBuildInfoFile);
            latestGraphBuildInfoInRepo.setPersistedGraphBuildInfo(persistedGraphBuildInfo);
        } else {
            LOGGER.error("[%s] graphBuildInfo 文件的下载路径无效: %s".formatted(getProfileDescriptiveName(), latestGraphBuildInfoInRepoPath));
        }

        return latestGraphBuildInfoInRepo;
    }

    public void downloadFile(Path repoPath, File localPath) {
        if (repoPath == null || localPath == null) {
            LOGGER.warn("[%s] 下载路径或本地路径无效: %s 或 %s".formatted(getProfileDescriptiveName(), repoPath, localPath));
            return;
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[%s] 正在将 %s 下载到本地文件 %s...".formatted(getProfileDescriptiveName(), repoPath.toFile().getAbsolutePath(), localPath.getAbsolutePath()));
        } else {
            LOGGER.info("[%s] 正在下载 %s...".formatted(getProfileDescriptiveName(), repoPath.toFile().getName()));
        }
        try {
            Files.copy(repoPath, localPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[%s] 捕获到 %s（下载 %s 时）".formatted(getProfileDescriptiveName(), e, repoPath.toFile().getAbsolutePath()));
            throw new IllegalArgumentException(e);
        }
    }
}
