package org.heigit.geovisroute.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SourceUtils {
    private SourceUtils() {
        // 私有构造函数，隐藏隐式的公有构造函数
    }

    private static final Logger logger = LoggerFactory.getLogger(SourceUtils.class);
    private static final String PROFILE_COLUMN = "profile";

    public static List<Map<String, Object>> getRecordsByProfile(List<Map<String, Object>> records, String targetProfile)
            throws IllegalStateException {
        // 从 CSV 读取所有记录
        logger.debug("已从 CSV 文件读取 {} 条记录", records.size());

        if (records.isEmpty()) {
            throw new IllegalStateException("No records found in CSV file: " + records);
        }

        // 记录第一条记录的样例以便调试
        logger.debug("示例记录结构：{}", records.get(0).keySet());

        // 如果存在 profile 列则按配置方案分组记录，否则使用所有记录
        Map<String, List<Map<String, Object>>> recordsByProfile;
        if (records.isEmpty() || !records.get(2).containsKey(PROFILE_COLUMN)) {
            // 如果不存在 profile 列，将所有记录放在一个默认键下
            recordsByProfile = Map.of("all", records);
            logger.debug("CSV 中不存在 profile 列，使用全部 {} 个坐标", records.size());
        } else {
            // 按配置方案分组记录
            recordsByProfile = records.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            coordinateRecord -> (String) coordinateRecord.getOrDefault(PROFILE_COLUMN,
                                    targetProfile)));
            logger.debug("已找到以下 profile 的坐标：{}", recordsByProfile.keySet());
        }

        List<Map<String, Object>> targetRecords = recordsByProfile.getOrDefault(targetProfile,
                recordsByProfile.get("all"));

        if (targetRecords == null || targetRecords.isEmpty()) {
            throw new IllegalStateException(
                    "No records found for profile '" + targetProfile + "' in file " + records);
        }

        logger.debug("已选择 {} 条记录，对应 profile '{}'", targetRecords.size(), targetProfile);
        try {
            // 打乱记录顺序
            logger.debug("正在打乱 profile {} 的记录顺序", targetProfile);
            List<Map<String, Object>> mutableRecords = new ArrayList<>(targetRecords);
            Collections.shuffle(mutableRecords);
            targetRecords = mutableRecords;
            logger.debug("已打乱 {} 条记录，对应 profile {}", targetRecords.size(), targetProfile);
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("Failed to shuffle records", e);
        }
        return targetRecords;
    }
}
