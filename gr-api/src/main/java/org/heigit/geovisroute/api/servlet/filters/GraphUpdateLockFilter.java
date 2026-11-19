package org.heigit.geovisroute.api.servlet.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.heigit.geovisroute.routing.GraphUpdateLock;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 为读取路网图的请求加读锁。
 * <p>
 * 运行时加载自定义路网时持有写锁，此时图相关请求在短暂等待后仍无法获取读锁，直接返回 503，
 * 避免请求长时间阻塞。路网更新接口自身不经过该读锁，由接口持有写锁。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GraphUpdateLockFilter extends OncePerRequestFilter {
    private static final long READ_LOCK_WAIT_SECONDS = 2;
    private static final List<String> GRAPH_PATHS = List.of(
            "/directions", "/isochrones", "/snap", "/matrix", "/export",
            "/offroad", "/hybrid", "/status", "/custom-roads");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getServletPath();
        boolean isUpdateRequest = "/custom-roads".equals(path)
                && "POST".equalsIgnoreCase(request.getMethod());

        if (isUpdateRequest || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || GRAPH_PATHS.stream().noneMatch(path::startsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean acquired;
        try {
            acquired = GraphUpdateLock.tryReadLock(READ_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeUnavailable(response, "请求被中断，请稍后重试");
            return;
        }
        if (!acquired) {
            writeUnavailable(response, "路网更新中，请稍后重试");
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            GraphUpdateLock.unlockRead();
        }
    }

    private void writeUnavailable(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
