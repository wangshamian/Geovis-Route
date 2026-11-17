package org.heigit.geovisroute.coordinates_generator;

import org.heigit.geovisroute.coordinates_generator.cli.MatrixCommandLineParser;
import org.heigit.geovisroute.coordinates_generator.generators.CoordinateGeneratorMatrix;
import org.heigit.geovisroute.coordinates_generator.model.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * 用于生成矩阵测试坐标的命令行应用。
 * 它生成用于矩阵 API 测试的坐标，类似于
 * RouteGeneratorApp
 * 为 directions API 测试生成坐标的方式。
 */
public class MatrixGeneratorApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(MatrixGeneratorApp.class);

    public static void main(String[] args) {
        try {
            MatrixCommandLineParser cli = new MatrixCommandLineParser(args);

            if (cli.hasHelp()) {
                cli.printHelp();
                return;
            }

            LOGGER.info("正在创建用于矩阵的坐标生成器...");
            CoordinateGeneratorMatrix generator = cli.createGenerator();

            LOGGER.info("正在生成 {} 个矩阵...", generator.getNumMatrices());
            generator.generateMatrices();
            LOGGER.info("\n");

            List<Matrix> result = generator.getResult();
            LOGGER.info("正在将 {} 个矩阵写入 {}", result.size(), cli.getOutputFile());
            generator.writeToCSV(cli.getOutputFile());

            LOGGER.info("\n");
            LOGGER.info("已成功生成 {} 个矩阵", result.size());
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