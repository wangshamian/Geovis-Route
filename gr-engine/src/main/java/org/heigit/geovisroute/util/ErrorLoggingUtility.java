package org.heigit.geovisroute.util;

import org.apache.log4j.Logger;

public class ErrorLoggingUtility {
    private ErrorLoggingUtility() {
    }

    public static void logMissingConfigParameter(Class originalClass, String missingConfigParameter) {
        final Logger logger = Logger.getLogger(originalClass.getName());
        logger.error("缺少配置参数 " + missingConfigParameter);
    }

    public static void logMissingConfigParameter(Class originalClass, String missingConfigParameter, String message) {
        final Logger logger = Logger.getLogger(originalClass.getName());
        logger.error("配置参数 " + missingConfigParameter + " 引发了错误，错误信息如下: " + message);
    }
}
