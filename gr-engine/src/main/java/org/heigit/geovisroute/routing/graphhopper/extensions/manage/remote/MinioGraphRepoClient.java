package org.heigit.geovisroute.routing.graphhopper.extensions.manage.remote;

import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.heigit.geovisroute.exceptions.GRGraphFileManagerException;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphManagementRuntimeProperties;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.PersistedGraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.local.GRGraphFileManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Strings.isNullOrEmpty;

public class MinioGraphRepoClient extends AbstractGraphRepoClient implements GRGraphRepoClient {

    private static final Logger LOGGER = Logger.getLogger(MinioGraphRepoClient.class.getName());
    private final GraphManagementRuntimeProperties managementProps;
    private final GRGraphFileManager orsGraphFileManager;
    private final GRGraphRepoStrategy orsGraphRepoStrategy;
    private MinioClient minioClient;

    public MinioGraphRepoClient(GraphManagementRuntimeProperties managementProps, GRGraphRepoStrategy orsGraphRepoStrategy, GRGraphFileManager orsGraphFileManager) {
        this.managementProps = managementProps;
        this.orsGraphRepoStrategy = orsGraphRepoStrategy;
        this.orsGraphFileManager = orsGraphFileManager;
    }

    String getProfileDescriptiveName() {
        return orsGraphFileManager.getProfileDescriptiveName();
    }

    @Override
    public void downloadGraphIfNecessary() {
        if (isNullOrEmpty(String.valueOf(managementProps.getDerivedRepoBaseUrl())) || isNullOrEmpty(managementProps.getRepoName()) || isNullOrEmpty(managementProps.getRepoCoverage()) || isNullOrEmpty(managementProps.getGraphVersion())) {
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

            Path latestCompressedGraphInRepoPath = Path.of(managementProps.getRepoProfileGroup(), managementProps.getRepoCoverage(), managementProps.getGraphVersion(), orsGraphRepoStrategy.getRepoCompressedGraphFileName());
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

    private void deleteFileWithLogging(File file) {
        try {
            if (Files.deleteIfExists(file.toPath()))
                LOGGER.debug("[%s] 已删除旧的已下载 graphBuildInfo 文件: %s".formatted(getProfileDescriptiveName(), file.getAbsolutePath()));
        } catch (IOException e) {
            LOGGER.error("[%s] 无法删除旧的已下载 graphBuildInfo 文件: %s".formatted(getProfileDescriptiveName(), file.getAbsolutePath()));
        }
    }

    public static String concatenateToUrlPath(String... values) {
        return Stream.of(values)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(s -> s.replaceAll("^/", ""))
                .map(s -> s.replaceAll("/$", ""))
                .filter(s -> !s.equals("."))
                .collect(Collectors.joining("/"));
    }

    GraphBuildInfo downloadLatestGraphBuildInfoFromRepository() throws GRGraphFileManagerException {
        GraphBuildInfo graphBuildInfoInRepo = new GraphBuildInfo();
        LOGGER.debug("[%s] 正在检查远程仓库中最新的 graphBuildInfo...".formatted(getProfileDescriptiveName()));

        Path latestGraphBuildInfoInRepoPath = Path.of(managementProps.getRepoProfileGroup(), managementProps.getRepoCoverage(), managementProps.getGraphVersion(), orsGraphRepoStrategy.getRepoGraphBuildInfoFileName());

        File downloadedGraphBuildInfoFile = orsGraphFileManager.getDownloadedGraphBuildInfoFile();
        deleteFileWithLogging(downloadedGraphBuildInfoFile);
        downloadFile(latestGraphBuildInfoInRepoPath, downloadedGraphBuildInfoFile);

        if (!downloadedGraphBuildInfoFile.exists()) {
            LOGGER.info("[%s] 远程仓库中未找到 graphBuildInfo。".formatted(getProfileDescriptiveName()));
            return graphBuildInfoInRepo;
        }

        graphBuildInfoInRepo.withRemoteUriString(concatenateToUrlPath(managementProps.getRepoBaseUri(), managementProps.getRepoName(), latestGraphBuildInfoInRepoPath.toString()));
        PersistedGraphBuildInfo persistedGraphBuildInfo = orsGraphFileManager.readOrsGraphBuildInfo(downloadedGraphBuildInfoFile);
        graphBuildInfoInRepo.setPersistedGraphBuildInfo(persistedGraphBuildInfo);
        return graphBuildInfoInRepo;
    }

    public void downloadFile(Path repoPath, File localPath) {
        if (repoPath == null || localPath == null) {
            LOGGER.warn("[%s] 下载路径或本地路径无效: %s 或 %s".formatted(getProfileDescriptiveName(), repoPath, localPath));
            return;
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[%s] 正在将 %s 下载到本地文件 %s...".formatted(getProfileDescriptiveName(), repoPath, localPath.getAbsolutePath()));
        } else {
            LOGGER.info("[%s] 正在下载 %s...".formatted(getProfileDescriptiveName(), repoPath));
        }
        try {
            if (minioClient == null) {
                this.minioClient = MinioClient.builder()
                        .endpoint(managementProps.getRepoBaseUri().replaceAll("minio:", ""))
                        .credentials(managementProps.getRepoUser(), managementProps.getRepoPass())
                        .build();
            }
            minioClient.downloadObject(
                    DownloadObjectArgs.builder()
                            .bucket(managementProps.getRepoName())
                            .object(repoPath.toString())
                            .filename(localPath.toString())
                            .build()
            );
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            LOGGER.warn("[%s] 捕获到 %s（下载 %s 时）".formatted(getProfileDescriptiveName(), e, repoPath.toFile().getAbsolutePath()));
            throw new IllegalArgumentException(e);
        }
    }
}
