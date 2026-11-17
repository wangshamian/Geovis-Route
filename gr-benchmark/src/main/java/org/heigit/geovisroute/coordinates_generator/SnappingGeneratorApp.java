package org.heigit.geovisroute.coordinates_generator;

import org.heigit.geovisroute.coordinates_generator.cli.SnappingCommandLineParser;
import org.heigit.geovisroute.coordinates_generator.generators.CoordinateGeneratorSnapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SnappingGeneratorApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(SnappingGeneratorApp.class);

    public static void main(String[] args) {
        try {
            SnappingCommandLineParser cli = new SnappingCommandLineParser(args);

            if (cli.hasHelp()) {
                cli.printHelp();
                return;
            }

            LOGGER.info("正在创建用于 snapping 的坐标生成器...");
            CoordinateGeneratorSnapping generator = cli.createGenerator();

            LOGGER.info("正在生成并吸附 {} 个点...", generator.getNumPoints());
            generator.generate();

            LOGGER.info("\n");
            LOGGER.info("正在将 {} 个已吸附的点写入 {}", generator.getResult().size(), cli.getOutputFile());
            generator.writeToCSV(cli.getOutputFile());

            LOGGER.info("已成功吸附 {} 个坐标", generator.getResult().size());
            LOGGER.info("结果已写入：{}", cli.getOutputFile());

        } catch (NumberFormatException e) {
            LOGGER.error("解析数值参数时出错：{}", e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            LOGGER.error("写入输出文件时出错：{}", e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }
}
