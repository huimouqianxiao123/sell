package com.example.sell;

import okhttp3.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class MiaoshaTest {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String LOGIN_URL = "http://localhost:8080/auth/login";
    private static final String SECKILL_URL = "http://localhost:8080/api/startSeckill";
    private static final String PASSWORD = "123456";
    private static final Long SECKILL_PRODUCT_ID = 1L;

    private List<String> tokens = new ArrayList<>();

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicInteger duplicateCount = new AtomicInteger(0);
    private final AtomicInteger soldOutCount = new AtomicInteger(0);
    private final List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());

    @Test
    public void testMiaosha() {
        // 假设用户名从 1001 到 1020（如果是 "1001i" 格式请改为 String username = "1001" + i;）
        for (int i = 0; i < 20; i++) {
            String username = String.valueOf(1001 + i);  // 生成 1001, 1002, ...

            String json = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, PASSWORD);

            RequestBody requestBody = RequestBody.create(json, JSON_MEDIA_TYPE);
            Request request = new Request.Builder()
                    .url(LOGIN_URL)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String result = response.body().string();

                    // 解析 JSON 提取 token（根据你实际的返回结构调整）
                    String token = extractToken(result);

                    if (token != null && !token.isEmpty()) {
                        tokens.add(token);
                        System.out.printf("用户 %s 登录成功，Token: %s...%n",
                                username, token.substring(0, Math.min(20, token.length())));
                    } else {
                        System.err.println("用户 " + username + " 响应中无 token: " + result);
                    }
                } else {
                    System.err.println("用户 " + username + " 登录失败: HTTP " + response.code());
                }
            } catch (IOException e) {
                System.err.println("用户 " + username + " 请求异常: " + e.getMessage());
            }
        }

        System.out.println("-----------------------");
        System.out.println("成功获取 " + tokens.size() + " 个 token，准备开始秒杀...");

        resetCounters();
        testConcurrentSeckill();
    }

    @Test
    public void testConcurrentSeckill() {
        if (tokens.isEmpty()) {
            System.out.println("没有可用的 token，请先执行 testMiaosha() 获取 token");
            return;
        }

        System.out.println("====================== 开始并发秒杀测试 ======================");
        System.out.println("并发用户数: " + tokens.size());
        System.out.println("秒杀商品ID: " + SECKILL_PRODUCT_ID);
        System.out.println("============================================================");

        ExecutorService executorService = Executors.newFixedThreadPool(tokens.size());
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(tokens.size());

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < tokens.size(); i++) {
            final int index = i;
            final String token = tokens.get(i);

            executorService.submit(() -> {
                try {
                    startLatch.await();
                    long requestStart = System.currentTimeMillis();
                    
                    String result = doSeckill(token);
                    long requestEnd = System.currentTimeMillis();
                    long responseTime = requestEnd - requestStart;
                    
                    responseTimes.add(responseTime);
                    
                    System.out.printf("用户 %d (token: %s...) 秒杀结果: %s, 耗时: %dms%n",
                            index + 1,
                            token.substring(0, Math.min(10, token.length())),
                            result,
                            responseTime);
                    
                    if (result.contains("成功")) {
                        successCount.incrementAndGet();
                    } else if (result.contains("重复购买")) {
                        duplicateCount.incrementAndGet();
                    } else if (result.contains("售罄")) {
                        soldOutCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("用户 " + (index + 1) + " 秒杀异常: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();

        try {
            endLatch.await();
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;

            printStatistics(totalTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }
    }

    private String doSeckill(String token) {
        String url = SECKILL_URL + "?id=" + SECKILL_PRODUCT_ID;
        
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                return "HTTP错误: " + response.code();
            }
        } catch (IOException e) {
            return "请求异常: " + e.getMessage();
        }
    }

    private void resetCounters() {
        successCount.set(0);
        failCount.set(0);
        duplicateCount.set(0);
        soldOutCount.set(0);
        responseTimes.clear();
    }

    private void printStatistics(long totalTime) {
        System.out.println("\n====================== 测试统计结果 ======================");
        System.out.println("总请求数: " + tokens.size());
        System.out.println("成功数: " + successCount.get());
        System.out.println("失败数: " + failCount.get());
        System.out.println("重复购买数: " + duplicateCount.get());
        System.out.println("售罄数: " + soldOutCount.get());
        System.out.println("成功率: " + String.format("%.2f%%", successCount.get() * 100.0 / tokens.size()));
        System.out.println("总耗时: " + totalTime + "ms");
        System.out.println("QPS: " + String.format("%.2f", tokens.size() * 1000.0 / totalTime));
        
        if (!responseTimes.isEmpty()) {
            Collections.sort(responseTimes);
            long minTime = responseTimes.get(0);
            long maxTime = responseTimes.get(responseTimes.size() - 1);
            long avgTime = responseTimes.stream().mapToLong(Long::longValue).sum() / responseTimes.size();
            long medianTime = responseTimes.get(responseTimes.size() / 2);
            
            System.out.println("\n响应时间统计:");
            System.out.println("最小响应时间: " + minTime + "ms");
            System.out.println("最大响应时间: " + maxTime + "ms");
            System.out.println("平均响应时间: " + avgTime + "ms");
            System.out.println("中位数响应时间: " + medianTime + "ms");
        }
        System.out.println("============================================================");
    }

    /**
     * 提取 token（根据实际 JSON 结构调整）
     */
    private String extractToken(String jsonStr) {
        try {
            JSONObject json = JSON.parseObject(jsonStr);

            // 常见格式 1：{"code":200,"data":{"token":"xxx"},"msg":"success"}
            if (json.containsKey("data") && json.get("data") instanceof JSONObject) {
                JSONObject data = json.getJSONObject("data");
                if (data.containsKey("token")) {
                    return data.getString("token");
                }
            }

            // 常见格式 2：{"token":"xxx","expire":7200}
            if (json.containsKey("token")) {
                return json.getString("token");
            }

            // 常见格式 3：直接返回字符串 token（无 JSON 包装）
            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                return jsonStr.replaceAll("\"", "");
            }

        } catch (Exception e) {
            System.err.println("JSON 解析失败: " + jsonStr);
        }
        return null;
    }

    // Getter 供其他测试方法使用
    public List<String> getTokens() {
        return tokens;
    }
}