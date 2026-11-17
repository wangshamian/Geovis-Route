package org.heigit.geovisroute.coordinates_generator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

public class CoordinateSnapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoordinateSnapper.class);
    private static final double DEFAULT_SNAP_RADIUS = 350; // 吸附半径为 350 米
    private static final String LOCATIONS_KEY = "locations";
    private static final String LOCATION_KEY = "location";

    private final String baseUrl;
    private final Map<String, String> headers;
    private final ObjectMapper mapper;
    private final Function<HttpPost, String> requestExecutor;

    public CoordinateSnapper(String baseUrl, Map<String, String> headers, ObjectMapper mapper,
                             Function<HttpPost, String> requestExecutor) {
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.mapper = mapper;
        this.requestExecutor = requestExecutor;
    }
    
    public List<double[]> snapCoordinates(List<double[]> coordinates, String profile) {
        LOGGER.debug("正在吸附 {} 个坐标，profile 为 {}", coordinates.size(), profile);
        
        try {
            HttpPost request = createSnapRequest(coordinates, profile);
            String response = requestExecutor.apply(request);
            
            if (response == null) {
                LOGGER.debug("吸附 API 返回了 null 响应");
                return Collections.emptyList();
            }
            
            return processSnapResponse(response);
        } catch (IOException e) {
            LOGGER.error("吸附坐标时出错：{}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private HttpPost createSnapRequest(List<double[]> coordinates, String profile) throws JsonProcessingException {
        Map<String, Object> payload = new HashMap<>();
        payload.put(LOCATIONS_KEY, coordinates);
        payload.put("radius", DEFAULT_SNAP_RADIUS);

        HttpPost request = new HttpPost(baseUrl + "/snap/" + profile);
        headers.forEach(request::addHeader);
        request.setEntity(new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
        return request;
    }
    
    @SuppressWarnings("unchecked")
    private List<double[]> processSnapResponse(String response) throws JsonProcessingException {
        List<double[]> snappedCoordinates = new ArrayList<>();

        Map<String, Object> responseMap = mapper.readValue(response,
                new TypeReference<Map<String, Object>>() {});

        Object locationsObj = responseMap.get(LOCATIONS_KEY);
        if (!(locationsObj instanceof List<?>)) {
            LOGGER.debug("吸附响应中不包含有效的 locations 数组");
            return snappedCoordinates;
        }

        List<Map<String, Object>> locations = (List<Map<String, Object>>) locationsObj;
        for (Map<String, Object> location : locations) {
            if (location == null) continue;
            
            List<Number> coords = (List<Number>) location.get(LOCATION_KEY);
            if (coords != null && coords.size() >= 2) {
                double[] point = new double[] { coords.get(0).doubleValue(), coords.get(1).doubleValue() };
                snappedCoordinates.add(point);
                LOGGER.debug("已添加吸附后的坐标：[{}, {}]", point[0], point[1]);
            } else {
                LOGGER.debug("吸附响应中的 location 无效");
            }
        }

        return snappedCoordinates;
    }
}
