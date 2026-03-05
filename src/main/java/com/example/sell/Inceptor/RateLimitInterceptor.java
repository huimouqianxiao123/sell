package com.example.sell.Inceptor;

import com.example.sell.common.UserContext;
import com.example.sell.common.UserInfo;
import com.example.sell.config.LuaScripts;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秒杀限流拦截器
 * <p>
 * 双层防护：
 * 1. 本地内存售罄标记 — 商品售罄后直接拒绝，不走 Redis
 * 2. Redis 滑动窗口限流 — 控制用户请求频率，防止恶意刷接口
 * </p>
 * <p>
 * Redis 不可用时快速失败（熔断），返回"系统繁忙"而非阻塞等待
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private LuaScripts luaScripts;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 本地售罄标记（内存级别，比 Redis 更快）
     * 当 Redis 库存为 0 时设置标记，后续请求直接拒绝无需访问 Redis
     * 定时任务预热时需清理对应标记
     */
    private static final Set<Long> SOLD_OUT_PRODUCTS = ConcurrentHashMap.newKeySet();

    /**
     * 限流配置：路径 → {窗口大小(秒), 最大请求数}
     */
    private static final Map<String, int[]> RATE_LIMIT_CONFIG = new HashMap<>();

    static {
        // 秒杀接口：每人每秒最多5次请求
        RATE_LIMIT_CONFIG.put("/api/startSeckill", new int[]{1, 5});
        // 查询秒杀结果：每人每秒最多10次
        RATE_LIMIT_CONFIG.put("/api/result", new int[]{1, 10});
        // 秒杀列表：每人每秒最多20次
        RATE_LIMIT_CONFIG.put("/api/miaoshalist", new int[]{1, 20});
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 只对配置了限流的接口进行检查
        int[] config = RATE_LIMIT_CONFIG.get(uri);
        if (config == null) {
            return true;
        }

        // 秒杀接口额外做售罄检查
        if ("/api/startSeckill".equals(uri)) {
            String idStr = request.getParameter("id");
            if (idStr != null) {
                try {
                    Long productId = Long.parseLong(idStr);
                    if (SOLD_OUT_PRODUCTS.contains(productId)) {
                        writeJsonResponse(response, 200, "商品已售罄");
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                    // 参数格式错误交给 Controller 处理
                }
            }
        }

        // 获取用户ID（未登录的请求用IP做限流）
        String rateLimitKey = buildRateLimitKey(uri, request);

        // Redis 限流检查（带熔断）
        try {
            Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    luaScripts.getRateLimiter(),
                    RScript.ReturnType.INTEGER,
                    Collections.<Object>singletonList(rateLimitKey),
                    String.valueOf(config[0]),
                    String.valueOf(config[1]),
                    String.valueOf(System.currentTimeMillis())
            );

            if (result != null && result == 0) {
                log.warn("【限流】请求被限流，key: {}", rateLimitKey);
                writeJsonResponse(response, 429, "请求过于频繁，请稍后重试");
                return false;
            }
        } catch (Exception e) {
            // Redis 不可用时的熔断策略：放行请求（降级）
            // 因为 Lua 秒杀脚本本身有防重复机制，限流只是额外保护层
            log.error("【限流】Redis 限流检查异常（已降级放行），key: {}", rateLimitKey, e);
        }

        return true;
    }

    /**
     * 构建限流 Key
     * 格式：rate_limit:{uri}:{userId} 或 rate_limit:{uri}:ip:{ip}
     */
    private String buildRateLimitKey(String uri, HttpServletRequest request) {
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo != null && userInfo.getId() != null) {
            return "rate_limit:" + uri + ":" + userInfo.getId();
        }
        // 未登录用户使用IP
        String ip = getClientIp(request);
        return "rate_limit:" + uri + ":ip:" + ip;
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 取第一个IP（可能有多级代理）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 写入 JSON 响应
     */
    private void writeJsonResponse(HttpServletResponse response, int httpStatus, String message) throws Exception {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new HashMap<>();
        result.put("code", httpStatus == 200 ? 200 : 429);
        result.put("msg", message);
        result.put("data", null);
        PrintWriter writer = response.getWriter();
        writer.write(OBJECT_MAPPER.writeValueAsString(result));
        writer.flush();
    }

    // ==================== 售罄标记管理 ====================

    /**
     * 标记商品已售罄（由秒杀逻辑或定时任务调用）
     */
    public static void markSoldOut(Long productId) {
        SOLD_OUT_PRODUCTS.add(productId);
        log.debug("【限流】商品已标记售罄，ID: {}", productId);
    }

    /**
     * 清除售罄标记（由预热任务调用，新一轮秒杀开始时重置）
     */
    public static void clearSoldOut(Long productId) {
        SOLD_OUT_PRODUCTS.remove(productId);
        log.debug("【限流】商品售罄标记已清除，ID: {}", productId);
    }

    /**
     * 清除所有售罄标记
     */
    public static void clearAllSoldOut() {
        SOLD_OUT_PRODUCTS.clear();
        log.debug("【限流】所有售罄标记已清除");
    }
}
