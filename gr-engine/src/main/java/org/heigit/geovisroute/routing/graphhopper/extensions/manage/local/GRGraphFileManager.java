package org.heigit.geovisroute.routing.graphhopper.extensions.manage.local;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.graphhopper.GraphHopper;
import com.graphhopper.util.Helper;
import com.graphhopper.util.Unzipper;
import lombok.NoArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.heigit.geovisroute.config.profile.ProfileProperties;
import org.heigit.geovisroute.exceptions.GRGraphFileManagerException;
import org.heigit.geovisroute.routing.graphhopper.extensions.GRGraphHopper;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphBuildInfo;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.GraphManagementRuntimeProperties;
import org.heigit.geovisroute.routing.graphhopper.extensions.manage.PersistedGraphBuildInfo;
import org.heigit.geovisroute.util.AppInfo;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN;
import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.*;

@NoArgsConstructor
public class GRGraphFileManager implements GRGraphFolderStrategy {

    private static final Logger LOGGER = Logger.getLogger(GRGraphFileManager.class.getName());

    GraphManagementRuntimeProperties graphManagementRuntimeProperties;
    private GRGraphFolderStrategy orsGraphFolderStrategy;

    public GRGraphFileManager(GraphManagementRuntimeProperties graphManagementRuntimeProperties, GRGraphFolderStrategy orsGraphFolderStrategy) {
        this.graphManagementRuntimeProperties = graphManagementRuntimeProperties;
        this.orsGraphFolderStrategy = orsGraphFolderStrategy;
    }

    public void initialize() {
        File activeGraphDirectory = getActiveGraphDirectory();
        if (!activeGraphDirectory.exists()) {
            LOGGER.debug("[%s] 正在创建图数据目录 %s".formatted(getProfileDescriptiveName(), activeGraphDirectory.getAbsolutePath()));
            if (!activeGraphDirectory.mkdirs()) {
                LOGGER.error("[%s] 无法创建图数据目录 %s".formatted(getProfileDescriptiveName(), activeGraphDirectory.getAbsolutePath()));
            }
        }
    }

    public boolean hasActiveGraph() {
        return isExistingDirectoryWithFiles(getActiveGraphDirectory());
    }

    public boolean hasActiveGraphDirectory() {
        return isExistingDirectory(getActiveGraphDirectory());
    }

    public boolean hasGraphDownloadFile() {
        return getDownloadedCompressedGraphFile().exists();
    }

    public boolean hasDownloadedExtractedGraph() {
        return isExistingDirectoryWithFiles(getDownloadedExtractedGraphDirectory());
    }

    boolean isExistingDirectory(File directory) {
        return directory.exists() && directory.isDirectory();
    }

    boolean isExistingDirectoryWithFiles(File directory) {
        return isExistingDirectory(directory) && directory.listFiles().length > 0;
    }

    public File asIncompleteFile(File file) {
        return new File(file.getAbsolutePath() + "." + INCOMPLETE_EXTENSION);
    }

    File asIncompleteDirectory(File directory) {
        return new File(directory.getAbsolutePath() + "_" + INCOMPLETE_EXTENSION);
    }

    public boolean isBusy() {
        return asIncompleteFile(getDownloadedCompressedGraphFile()).exists() ||
                asIncompleteFile(getDownloadedGraphBuildInfoFile()).exists() ||
                asIncompleteFile(getDownloadedExtractedGraphDirectory()).exists();
    }

    private void deleteFileWithLogging(File file, String successMessage, String errorMessage) {
        try {
            if (Files.deleteIfExists(file.toPath()))
                LOGGER.debug(successMessage.formatted(getProfileDescriptiveName(), file.getAbsolutePath()));
        } catch (IOException e) {
            LOGGER.error(errorMessage.formatted(e.getMessage()));
        }
    }

    public void cleanupIncompleteFiles() {
        File incompleteDownloadFile = asIncompleteFile(getDownloadedCompressedGraphFile());
        deleteFileWithLogging(incompleteDownloadFile,
                "[%s] 已删除上次应用运行遗留的未完成图下载文件: %s",
                "删除未完成下载文件时出错: %s");

        File graphBuildInfoDownloadFile = getDownloadedGraphBuildInfoFile();
        deleteFileWithLogging(graphBuildInfoDownloadFile,
                "[%s] 已删除上次应用运行遗留的 graph-info 下载文件: %s",
                "删除 graph-info 下载文件时出错: %s");

        File incompleteGraphBuildInfoDownloadFile = asIncompleteFile(getDownloadedGraphBuildInfoFile());
        deleteFileWithLogging(incompleteGraphBuildInfoDownloadFile,
                "[%s] 已删除上次应用运行遗留的未完成图下载文件: %s",
                "删除未完成图下载文件时出错: %s");

        File incompleteExtractionFolder = asIncompleteDirectory(getDownloadedExtractedGraphDirectory());
        if (incompleteExtractionFolder.exists() && incompleteExtractionFolder.isDirectory()) {
            try {
                FileUtils.deleteDirectory(incompleteExtractionFolder);
                LOGGER.debug("[%s] 已删除上次应用运行遗留的未完成图解压目录: %s".formatted(getProfileDescriptiveName(), incompleteExtractionFolder.getAbsolutePath()));
            } catch (IOException e) {
                LOGGER.error("[%s] 无法删除上次应用运行遗留的未完成图解压目录: %s".formatted(getProfileDescriptiveName(), incompleteExtractionFolder.getAbsolutePath()));
            }
        }
    }

    public void backupExistingGraph() {
        if (!hasActiveGraph()) {
            deleteOldestBackups();
            return;
        }
        File activeGraphDirectory = getActiveGraphDirectory();
        String origAbsPath = activeGraphDirectory.getAbsolutePath();
        String dateString = DateTimeFormatter.ofPattern("uuuu-MM-dd_HHmmss", Locale.getDefault()).format(LocalDateTime.now());
        String newAbsPath = activeGraphDirectory.getAbsolutePath() + "_" + dateString;
        File backupFile = new File(newAbsPath);

        if (backupFile.exists()) {
            LOGGER.debug("[%s] 正在删除旧备份目录 %s".formatted(getProfileDescriptiveName(), newAbsPath));
            try {
                FileUtils.deleteDirectory(backupFile);
                backupFile = new File(newAbsPath);
            } catch (IOException e) {
                LOGGER.warn("[%s] 无法删除旧备份目录 %s，捕获异常 %s".formatted(getProfileDescriptiveName(), newAbsPath, e.getMessage()));
            }
        }

        if (activeGraphDirectory.renameTo(backupFile)) {
            LOGGER.debug("[%s] 已将旧本地图数据目录 %s 重命名为 %s".formatted(getProfileDescriptiveName(), origAbsPath, newAbsPath));
        } else {
            LOGGER.error("[%s] 无法将本地图数据目录 %s 备份为 %s".formatted(getProfileDescriptiveName(), origAbsPath, newAbsPath));
        }
        deleteOldestBackups();
    }

    public void deleteOldestBackups() {
        List<File> existingBackups = findGraphBackupsSortedByName();
        int numBackupsToDelete = existingBackups.size() - Math.max(graphManagementRuntimeProperties.getMaxNumberOfGraphBackups(), 0);
        if (numBackupsToDelete < 1) {
            return;
        }
        List<File> backupsToDelete = existingBackups.subList(0, numBackupsToDelete);
        for (File backupFile : backupsToDelete) {
            try {
                LOGGER.debug("[%s] 正在删除旧备份目录 %s".formatted(getProfileDescriptiveName(), backupFile.getAbsolutePath()));
                FileUtils.deleteDirectory(backupFile);
            } catch (IOException e) {
                LOGGER.warn("[%s] 无法删除旧备份目录 %s，捕获异常 %s".formatted(getProfileDescriptiveName(), backupFile.getAbsolutePath(), e.getMessage()));
            }
        }
    }

    public List<File> findGraphBackupsSortedByName() {
        File vehicleDir = getProfileGraphsDirectory();
        FilenameFilter filter = new RegexFileFilter("^%s_\\d{4}-\\d{2}-\\d{2}_\\d{6}$".formatted(getActiveGraphDirName()));
        File[] obj = vehicleDir.listFiles(filter);
        if (obj == null)
            return Collections.emptyList();

        return Arrays.asList(Objects.requireNonNull(obj)).stream().sorted(Comparator.comparing(File::getName)).toList();
    }

    public GraphBuildInfo getActiveGraphBuildInfo() throws GRGraphFileManagerException {
        LOGGER.trace("[%s] 正在检查活动图信息...".formatted(getProfileDescriptiveName()));
        File activeGraphDirectory = getActiveGraphDirectory();

        if (!hasActiveGraph()) {
            LOGGER.trace("[%s] 未找到活动图数据目录。".formatted(getProfileDescriptiveName()));
            return new GraphBuildInfo().setLocalDirectory(activeGraphDirectory);
        }

        return getGraphBuildInfo(getActiveGraphBuildInfoFile());
    }

    public GraphBuildInfo getDownloadedExtractedGraphBuildInfo() throws GRGraphFileManagerException {
        LOGGER.trace("[%s] 正在检查已下载图信息...".formatted(getProfileDescriptiveName()));
        File downloadedExtractedGraphDirectory = getDownloadedExtractedGraphDirectory();

        if (!hasDownloadedExtractedGraph()) {
            LOGGER.trace("[%s] 未找到已下载图数据目录。".formatted(getProfileDescriptiveName()));
            return new GraphBuildInfo().setLocalDirectory(downloadedExtractedGraphDirectory);
        }

        return getGraphBuildInfo(getDownloadedExtractedGraphBuildInfoFile());
    }

    private GraphBuildInfo getGraphBuildInfo(File graphBuildInfoFile) throws GRGraphFileManagerException {
        File graphDirectory = graphBuildInfoFile.getParentFile();
        if (!graphBuildInfoFile.exists() || !graphBuildInfoFile.isFile()) {
            LOGGER.trace("[%s] 未找到图信息文件 %s（位于 %s）".formatted(getProfileDescriptiveName(), graphBuildInfoFile.getName(), graphBuildInfoFile.getParentFile().getName()));
            return new GraphBuildInfo().setLocalDirectory(graphDirectory);
        }

        PersistedGraphBuildInfo graphBuildInfo = readOrsGraphBuildInfo(graphBuildInfoFile);
        LOGGER.trace("[%s] 找到本地图信息，importDate=%s".formatted(getProfileDescriptiveName(), graphBuildInfo.getGraphBuildDate()));
        return new GraphBuildInfo().setLocalDirectory(graphDirectory).setPersistedGraphBuildInfo(graphBuildInfo);
    }

    static ObjectMapper getYamlMapper() {
        YAMLFactory yf = new YAMLFactory()
                .disable(WRITE_DOC_START_MARKER)
                .disable(SPLIT_LINES)
                .disable(USE_NATIVE_TYPE_ID)
                .enable(INDENT_ARRAYS_WITH_INDICATOR)
                .enable(MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(yf);
        mapper.configure(WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false);
        return mapper;
    }

    public PersistedGraphBuildInfo readOrsGraphBuildInfo(File graphBuildInfoFile) throws GRGraphFileManagerException {
        try {
            return getYamlMapper()
                    .readValue(graphBuildInfoFile, PersistedGraphBuildInfo.class);
        } catch (IOException e) {
            LOGGER.error("读取 ORS 图信息时出错: %s".formatted(e.getMessage()));
            throw new GRGraphFileManagerException("Error reading ORS graph info: ", e);
        }
    }

    public static void writeOrsGraphBuildInfo(PersistedGraphBuildInfo persistedGraphBuildInfo, File outputFile) {
        try {
            getYamlMapper()
                    .writeValue(outputFile, persistedGraphBuildInfo);
        } catch (IOException e) {
            LOGGER.error("无法写入文件 %s".formatted(outputFile.getAbsolutePath()));
            throw new GRGraphFileManagerException("Could not write file: ", e);
        }
    }

    public PersistedGraphBuildInfo getDownloadedGraphBuildInfo() throws GRGraphFileManagerException {
        LOGGER.trace("[%s] 正在检查上次检查的图信息...".formatted(getProfileDescriptiveName()));
        File downloadedGraphBuildInfoFile = getDownloadedGraphBuildInfoFile();
        if (downloadedGraphBuildInfoFile.exists()) {
            return readOrsGraphBuildInfo(downloadedGraphBuildInfoFile);
        }
        return null;
    }

    public void activateExtractedDownloadedGraph() {
        if (hasDownloadedExtractedGraph()) {
            LOGGER.info("[%s] 正在激活已解压的下载图数据。".formatted(getProfileDescriptiveName()));
            File downloadedExtractedGraphDirectory = getDownloadedExtractedGraphDirectory();
            if (downloadedExtractedGraphDirectory.renameTo(getActiveGraphDirectory())) {
                LOGGER.debug("[%s] 已成功激活解压后的下载图数据。".formatted(getProfileDescriptiveName()));
            } else {
                LOGGER.error("[%s] 激活解压后的下载图数据失败。".formatted(getProfileDescriptiveName()));
            }
        }
    }

    public void extractDownloadedGraph() {
        File graphDownloadFile = getDownloadedCompressedGraphFile();
        if (!graphDownloadFile.exists()) {
            LOGGER.debug("[%s] 没有可解压的已下载图数据。".formatted(getProfileDescriptiveName()));
            return;
        }

        String graphDownloadFileAbsPath = graphDownloadFile.getAbsolutePath();
        File targetDirectory = getDownloadedExtractedGraphDirectory();
        String targetDirectoryAbsPath = targetDirectory.getAbsolutePath();
        File extractionDirectory = asIncompleteDirectory(targetDirectory);
        String extractionDirectoryAbsPath = extractionDirectory.getAbsolutePath();

        if (isExistingDirectory(extractionDirectory)) {
            LOGGER.debug("[%s] 解压已开始。".formatted(getProfileDescriptiveName()));
            return;
        }

        try {
            LOGGER.info("[%s] 正在将下载的图文件解压到 %s".formatted(getProfileDescriptiveName(), extractionDirectoryAbsPath));
            long start = System.currentTimeMillis();
            (new Unzipper()).unzip(graphDownloadFileAbsPath, extractionDirectoryAbsPath, true);
            long end = System.currentTimeMillis();

            LOGGER.debug("[%s] 下载的图文件解压完成，耗时 %d ms，正在删除下载的图文件 %s".formatted(
                    getProfileDescriptiveName(),
                    end - start,
                    graphDownloadFileAbsPath));
            deleteFileWithLogging(graphDownloadFile,
                    "[%s] 已删除下载的图文件 %s".formatted(getProfileDescriptiveName(), graphDownloadFileAbsPath),
                    "删除下载的图文件时出错: %s");

            LOGGER.debug("[%s] 正在将解压目录重命名为 %s".formatted(
                    getProfileDescriptiveName(),
                    targetDirectoryAbsPath));
            if (targetDirectory.exists()) {
                FileUtils.deleteDirectory(targetDirectory);
            }
            if (!extractionDirectory.renameTo(targetDirectory)) {
                LOGGER.error("[%s] 无法将解压目录重命名为 %s".formatted(getProfileDescriptiveName(), targetDirectoryAbsPath));
            }

        } catch (IOException ioException) {
            LOGGER.error("[%s] 将 %s 解压到 %s 时出错 -> %s".formatted(
                    getProfileDescriptiveName(),
                    graphDownloadFileAbsPath,
                    extractionDirectoryAbsPath,
                    targetDirectoryAbsPath));
            throw new GRGraphFileManagerException("Error during extraction of downloaded graph file: ", ioException);
        }
        LOGGER.info("[%s] 下载的图数据已解压，将在下次图激活检查或应用启动时激活。".formatted(getProfileDescriptiveName()));
    }

    public void writeOrsGraphBuildInfoFileIfNotExists(GRGraphHopper gh) {
        if (gh.getEngineProperties().getProfiles() == null)
            return;
        if (gh.getEngineProperties().getProfiles().isEmpty())
            return;

        File activeGraphDirectory = getActiveGraphDirectory();
        File activeGraphBuildInfoFile = getActiveGraphBuildInfoFile();
        if (!activeGraphDirectory.exists() || !activeGraphDirectory.isDirectory() || !activeGraphDirectory.canWrite()) {
            LOGGER.debug("图数据目录 %s 不存在或不可写。".formatted(activeGraphBuildInfoFile.getName()));
            return;
        }
        if (activeGraphBuildInfoFile.exists()) {
            LOGGER.debug("GraphBuildInfo 文件 %s 已存在。".formatted(activeGraphBuildInfoFile.getName()));
            return;
        }
        ProfileProperties profileProperties = gh.getProfileProperties();
        if (profileProperties == null) {
            LOGGER.debug("profile %s 的配置不存在，无法写入 GraphBuildInfo 文件。".formatted(this.graphManagementRuntimeProperties.getLocalProfileName()));
            return;
        }
        PersistedGraphBuildInfo persistedGraphBuildInfo = PersistedGraphBuildInfo.withOsmDate(getDateFromGhProperty(gh, "datareader.data.date"));
        persistedGraphBuildInfo.setGraphBuildDate(getDateFromGhProperty(gh, "datareader.import.date"));
        persistedGraphBuildInfo.setGraphVersion(AppInfo.GRAPH_VERSION);
        persistedGraphBuildInfo.setProfileProperties(profileProperties);

        GRGraphFileManager.writeOrsGraphBuildInfo(persistedGraphBuildInfo, activeGraphBuildInfoFile);
    }

    Date getDateFromGhProperty(GraphHopper gh, String ghProperty) {
        try {
            String importDateString = gh.getProperties().get(ghProperty);
            if (StringUtils.isBlank(importDateString)) {
                return null;
            }
            DateFormat f = Helper.createFormatter();
            return f.parse(importDateString);
        } catch (ParseException e) {
            LOGGER.error("无法从 GraphHopper 属性 %s 解析日期".formatted(ghProperty));
            return null;
        }
    }


    @Override
    public String getProfileDescriptiveName() {
        return orsGraphFolderStrategy.getProfileDescriptiveName();
    }

    @Override
    public String getGraphBuildInfoFileNameInRepository() {
        return orsGraphFolderStrategy.getGraphBuildInfoFileNameInRepository();
    }

    @Override
    public String getGraphsRootDirName() {
        return orsGraphFolderStrategy.getGraphsRootDirName();
    }

    @Override
    public String getGraphsRootDirAbsPath() {
        return orsGraphFolderStrategy.getGraphsRootDirAbsPath();
    }

    @Override
    public String getProfileGraphsDirName() {
        return orsGraphFolderStrategy.getProfileGraphsDirName();
    }

    @Override
    public String getProfileGraphsDirAbsPath() {
        return orsGraphFolderStrategy.getProfileGraphsDirAbsPath();
    }

    @Override
    public String getActiveGraphDirName() {
        return orsGraphFolderStrategy.getActiveGraphDirName();
    }

    @Override
    public String getActiveGraphDirAbsPath() {
        return orsGraphFolderStrategy.getActiveGraphDirAbsPath();
    }

    @Override
    public String getActiveGraphBuildInfoFileName() {
        return orsGraphFolderStrategy.getActiveGraphBuildInfoFileName();
    }

    @Override
    public String getDownloadedGraphBuildInfoFileName() {
        return orsGraphFolderStrategy.getDownloadedGraphBuildInfoFileName();
    }

    @Override
    public String getDownloadedGraphBuildInfoFileAbsPath() {
        return orsGraphFolderStrategy.getDownloadedGraphBuildInfoFileAbsPath();
    }

    @Override
    public String getDownloadedCompressedGraphFileName() {
        return orsGraphFolderStrategy.getDownloadedCompressedGraphFileName();
    }

    @Override
    public String getDownloadedCompressedGraphFileAbsPath() {
        return orsGraphFolderStrategy.getDownloadedCompressedGraphFileAbsPath();
    }

    @Override
    public String getDownloadedExtractedGraphDirName() {
        return orsGraphFolderStrategy.getDownloadedExtractedGraphDirName();
    }

    @Override
    public String getDownloadedExtractedGraphDirAbsPath() {
        return orsGraphFolderStrategy.getDownloadedExtractedGraphDirAbsPath();
    }

}