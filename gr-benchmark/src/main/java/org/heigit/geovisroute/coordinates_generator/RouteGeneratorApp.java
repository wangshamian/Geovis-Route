package org.heigit.geovisroute.coordinates_generator;

import org.heigit.geovisroute.coordinates_generator.cli.RouteCommandLineParser;
import org.heigit.geovisroute.coordinates_generator.generators.CoordinateGeneratorRoute;
import org.heigit.geovisroute.coordinates_generator.model.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class RouteGeneratorApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteGeneratorApp.class);

    public static void main(String[] args) {
        try {
            RouteCommandLineParser cli = new RouteCommandLineParser(args);

            if (cli.hasHelp()) {
                cli.printHelp();
                return;
            }

            LOGGER.info("正在创建用于路线的坐标生成器...");
            CoordinateGeneratorRoute generator = cli.createGenerator();

            LOGGER.info("正在生成 {} 条路线...", generator.getNumRoutes());
            generator.generateRoutes();
            LOGGER.info("\n");

            List<Route> result = generator.getResult();
            LOGGER.info("正在将 {} 条路线写入 {}", result.size(), cli.getOutputFile());
            generator.writeToCSV(cli.getOutputFile());

            LOGGER.info("\n");
            LOGGER.info("已成功生成 {} 条路线", result.size());
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
