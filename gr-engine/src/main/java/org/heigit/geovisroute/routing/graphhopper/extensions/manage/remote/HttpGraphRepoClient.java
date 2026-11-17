package org.heigit.geovisroute.routing.graphhopper.extensions.manage.remote;

import lombok.NoArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.heigit.geovisroute.exceptions.GRGraphFileManagerException;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphManagementRuntimeProperties;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.PersistedGraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.local.GRGraphFileManager;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Strings.isNullOrEmpty;

@NoArgsConstructor
public class HttpGraphRepoClient extends AbstractGraphRepoClient implements GRGraphRepoClient {

    private static final Logger LOGGER = Logger.getLogger(HttpGraphRepoClient.class.getName());
    private GraphManagementRuntimeProperties managementProps;
    private GRGraphFileManager orsGraphFileManager;
    private GRGraphRepoStrategy orsGraphRepoStrategy;

    public HttpGraphRepoClient(GraphManagementRuntimeProperties managementProps, GRGraphRepoStrategy orsGraphRepoStrategy, GRGraphFileManager orsGraphFileManager) {
        this.managementProps = managementProps;
        this.orsGraphRepoStrategy = orsGraphRepoStrategy;
        this.orsGraphFileManager = orsGraphFileManager;
    }

    String getProfileDescriptiveName() {
        return orsGraphFileManager.getProfileDescriptiveName();
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

    public URL createDownloadUrl(String fileName) {
        String urlString = concatenateToUrlPath(this.managementProps.getDerivedRepoBaseUrl().toString(),
                this.managementProps.getRepoName(),
                this.managementProps.getRepoProfileGroup(),
                this.managementProps.getRepoCoverage(),
                this.managementProps.getGraphVersion(),
                fileName);

        try {
            return new URL(urlString);
        } catch (MalformedURLException e) {
            LOGGER.debug("[%s] 为 graphBuildInfo 文件生成的下载 URL 无效: %s".formatted(getProfileDescriptiveName(), urlString));
            return null;
        }
    }

    private void deleteFileWithLogging(File file, String successMessage, String errorMessage) {
        try {
            if (Files.deleteIfExists(file.toPath()))
                LOGGER.debug(successMessage.formatted(getProfileDescriptiveName(), file.getAbsolutePath()));
        } catch (IOException e) {
            LOGGER.error(errorMessage.formatted(e.getMessage()));
        }
    }

    @Override
    public void downloadGraphIfNecessary() {
        if (isNullOrEmpty(this.managementProps.getDerivedRepoBaseUrl().toString()) || isNullOrEmpty(this.managementProps.getRepoName()) || isNullOrEmpty(this.managementProps.getRepoCoverage()) || isNullOrEmpty(this.managementProps.getGraphVersion())) {
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
            GraphBuildInfo newlyDownloadedGraphBuildInfo = downloadGraphBuildInfoFromRepository();

            if (!shouldDownloadGraph(
                    getDateOrEpocStart(newlyDownloadedGraphBuildInfo),
                    getDateOrEpocStart(activeGraphBuildInfo),
                    getDateOrEpocStart(downloadedExtractedGraphBuildInfo),
                    getDateOrEpocStart(downloadedCompressedGraphFile, previouslyDownloadedGraphBuildInfo))) {
                LOGGER.info("[%s] 仓库中未找到更新的图数据。".formatted(getProfileDescriptiveName()));
                return;
            }

            downloadCompressedGraphFromRepository();

        } catch (Exception e) {
            LOGGER.error("[%s] 在检查图下载或下载图数据时捕获到异常:".formatted(getProfileDescriptiveName()), e);
        }
    }

    GraphBuildInfo downloadGraphBuildInfoFromRepository() throws GRGraphFileManagerException {
        GraphBuildInfo graphBuildInfoInRepo = new GraphBuildInfo();
        LOGGER.debug("[%s] 正在检查远程仓库中最新的 graphBuildInfo...".formatted(getProfileDescriptiveName()));

        URL downloadUrl = createDownloadUrl(orsGraphRepoStrategy.getRepoGraphBuildInfoFileName());
        if (downloadUrl == null) {
            return graphBuildInfoInRepo;
        }
        File downloadedGraphBuildInfoFile = orsGraphFileManager.getDownloadedGraphBuildInfoFile();
        deleteFileWithLogging(downloadedGraphBuildInfoFile, "[%s] 已删除旧的已下载 graphBuildInfo 文件: %s", "[%s] 无法删除旧的已下载 graphBuildInfo 文件: %s");
        downloadFile(downloadUrl, downloadedGraphBuildInfoFile);
        if (!downloadedGraphBuildInfoFile.exists()) {
            LOGGER.info("[%s] 远程仓库中未找到 graphBuildInfo。".formatted(getProfileDescriptiveName()));
            return graphBuildInfoInRepo;
        }

        graphBuildInfoInRepo.withRemoteUrl(downloadUrl);
        PersistedGraphBuildInfo persistedGraphBuildInfo = orsGraphFileManager.readOrsGraphBuildInfo(downloadedGraphBuildInfoFile);
        graphBuildInfoInRepo.setPersistedGraphBuildInfo(persistedGraphBuildInfo);
        return graphBuildInfoInRepo;
    }

    void downloadCompressedGraphFromRepository() {
        URL downloadUrl = createDownloadUrl(orsGraphRepoStrategy.getRepoCompressedGraphFileName());
        if (downloadUrl == null) {
            return;
        }
        File downloadedFile = orsGraphFileManager.getDownloadedCompressedGraphFile();
        deleteFileWithLogging(downloadedFile, "[%s] 已删除旧的已下载压缩图文件: %s", "[%s] 无法删除旧的已下载压缩图文件: %s");

        long start = System.currentTimeMillis();
        downloadFile(downloadUrl, downloadedFile);//mocked!!!
        long end = System.currentTimeMillis();

        if (orsGraphFileManager.getDownloadedCompressedGraphFile().exists()) {
            LOGGER.info("[%s] 压缩图文件下载完成，耗时 %d ms".formatted(getProfileDescriptiveName(), end - start));
        } else {
            LOGGER.info("[%s] 远程仓库中未找到压缩图文件。".formatted(getProfileDescriptiveName()));
        }
    }

    public void downloadFile(URL downloadUrl, File outputFile) {
        File tempDownloadFile = orsGraphFileManager.asIncompleteFile(outputFile);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[%s] 正在将 %s 下载到本地文件 %s...".formatted(getProfileDescriptiveName(), downloadUrl, outputFile.getAbsolutePath()));
        } else {
            LOGGER.info("[%s] 正在下载 %s...".formatted(getProfileDescriptiveName(), downloadUrl));
        }
        try {
            int connectionTimeoutMillis = 2000;
            int readTimeoutMillis = 200000;
            FileUtils.copyURLToFile(
                    downloadUrl,
                    tempDownloadFile,
                    connectionTimeoutMillis,
                    readTimeoutMillis);
            if (tempDownloadFile.renameTo(outputFile)) {
                LOGGER.debug("[%s] 已将临时文件重命名为 %s".formatted(getProfileDescriptiveName(), outputFile.getAbsolutePath()));
            } else {
                LOGGER.error("[%s] 无法将临时文件重命名为 %s".formatted(getProfileDescriptiveName(), outputFile.getAbsolutePath()));
            }
        } catch (IOException e) {
            LOGGER.warn("[%s] 捕获到 %s（下载 %s 时）".formatted(getProfileDescriptiveName(), e.getClass().getName(), downloadUrl));
        } finally {
            deleteFileWithLogging(tempDownloadFile, "[%s] 已删除临时下载文件: %s", "[%s] 无法删除临时下载文件: %s");
        }
    }
}
