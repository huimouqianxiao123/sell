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
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest
public class MiaoshaStressTest {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(200, 5, TimeUnit.MINUTES))
            .build();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String LOGIN_URL = "http://localhost:8080/auth/login";
    private static final String SECKILL_URL = "http://localhost:8080/api/startSeckill";
    private static final String PASSWORD = "123456";

    private List<String> tokens = new ArrayList<>();

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicInteger duplicateCount = new AtomicInteger(0);
    private final AtomicInteger soldOutCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());

    // QPS 实时统计
    private final AtomicInteger completedRequests = new AtomicInteger(0);
    private final AtomicLong firstRequestTime = new AtomicLong(0);
    private final AtomicLong lastRequestTime = new AtomicLong(0);

    // 分时段 QPS 统计（每秒的请求数）
    private final ConcurrentHashMap<Long, AtomicInteger> qpsPerSecond = new ConcurrentHashMap<>();

    @Test
    public void testStressSeckill() {
        int userCount = 100;
        int threadCount = 50;
        Long productId = 1L;

        loginUsers(userCount);

        if (tokens.isEmpty()) {
            System.out.println("没有可用的 token，测试终止");
            return;
        }

        resetCounters();
        stressTest(threadCount, productId);
    }

    @Test
    public void testCustomStress() {
        Scanner scanner = new Scanner(System.in);

        System.out.print("请输入用户数量: ");
        int userCount = 3000;

        int threadCount = 200;

        Long productId = 2018236130249027586L;

        scanner.close();

        System.out.println("\n开始登录 " + userCount + " 个用户...");
        long loginStart = System.currentTimeMillis();
        loginUsers(userCount);
        long loginEnd = System.currentTimeMillis();
        System.out.println("登录完成，耗时: " + (loginEnd - loginStart) + "ms");

        if (tokens.isEmpty()) {
            System.out.println("没有可用的 token，测试终止");
            return;
        }

        resetCounters();
        stressTest(threadCount, productId);
    }

    private void loginUsers(int userCount) {
        System.out.println("开始登录 " + userCount + " 个用户...");

        ExecutorService executorService = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            final int userId = 1001 + i;
            executorService.submit(() -> {
                try {
                    String json = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", userId, PASSWORD);

                    RequestBody requestBody = RequestBody.create(json, JSON_MEDIA_TYPE);
                    Request request = new Request.Builder()
                            .url(LOGIN_URL)
                            .post(requestBody)
                            .header("Content-Type", "application/json")
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String result = response.body().string();
                            String token = extractToken(result);

                            if (token != null && !token.isEmpty()) {
                                tokens.add(token);
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("用户 " + userId + " 登录失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            System.out.println("成功登录 " + tokens.size() + " 个用户");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }
    }

    private void stressTest(int threadCount, Long productId) {
        System.out.println("\n====================== 开始压力测试 ======================");
        System.out.println("总用户数: " + tokens.size());
        System.out.println("并发线程数: " + threadCount);
        System.out.println("秒杀商品ID: " + productId);
        System.out.println("============================================================");

        // 重置 QPS 统计
        completedRequests.set(0);
        firstRequestTime.set(0);
        lastRequestTime.set(0);
        qpsPerSecond.clear();

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(tokens.size());

        // 实时 QPS 监控线程
        ScheduledExecutorService qpsMonitor = Executors.newSingleThreadScheduledExecutor();
        final long testStartTime = System.currentTimeMillis();

        qpsMonitor.scheduleAtFixedRate(() -> {
            int completed = completedRequests.get();
            long elapsed = System.currentTimeMillis() - testStartTime;
            if (elapsed > 0) {
                double currentQps = completed * 1000.0 / elapsed;
                int success = successCount.get();
                int soldOut = soldOutCount.get();
                System.out.printf("\r[实时] 已完成: %d/%d | 成功: %d | 售罄: %d | 当前QPS: %.2f",
                        completed, tokens.size(), success, soldOut, currentQps);
            }
        }, 500, 500, TimeUnit.MILLISECONDS);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < tokens.size(); i++) {
            final int index = i;
            final String token = tokens.get(i);

            executorService.submit(() -> {
                try {
                    startLatch.await();
                    long requestStart = System.currentTimeMillis();

                    // 记录第一个请求时间
                    firstRequestTime.compareAndSet(0, requestStart);

                    String result = doSeckill(token, productId);
                    long requestEnd = System.currentTimeMillis();
                    long responseTime = requestEnd - requestStart;

                    // 更新最后请求时间
                    lastRequestTime.updateAndGet(prev -> Math.max(prev, requestEnd));

                    // 记录分时段 QPS（按秒统计）
                    long secondKey = (requestEnd - testStartTime) / 1000;
                    qpsPerSecond.computeIfAbsent(secondKey, k -> new AtomicInteger(0)).incrementAndGet();

                    responseTimes.add(responseTime);
                    completedRequests.incrementAndGet();

                    if (result.contains("成功")) {
                        successCount.incrementAndGet();
                    } else if (result.contains("重复购买")) {
                        duplicateCount.incrementAndGet();
                    } else if (result.contains("售罄")) {
                        soldOutCount.incrementAndGet();
                    } else if (result.contains("未预热") || result.contains("未开始") || result.contains("已结束")) {
                        errorCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    completedRequests.incrementAndGet();
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

            // 停止 QPS 监控
            qpsMonitor.shutdown();
            System.out.println(); // 换行

            // 打印请求阶段统计
            printStatistics(totalTime);

            // 打印分时段 QPS
            printQpsPerSecond();

        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
            if (!qpsMonitor.isShutdown()) {
                qpsMonitor.shutdownNow();
            }
        }
    }

    /**
     * 打印每秒 QPS 统计
     */
    private void printQpsPerSecond() {
        System.out.println("\n====================== 分时段 QPS ======================");
        if (qpsPerSecond.isEmpty()) {
            System.out.println("无分时段数据");
            return;
        }

        List<Long> seconds = new ArrayList<>(qpsPerSecond.keySet());
        Collections.sort(seconds);

        int peakQps = 0;
        long peakSecond = 0;

        for (Long second : seconds) {
            int qps = qpsPerSecond.get(second).get();
            if (qps > peakQps) {
                peakQps = qps;
                peakSecond = second;
            }
            System.out.printf("第 %d 秒: %d 请求/秒%n", second + 1, qps);
        }

        System.out.println("------------------------------------------------------------");
        System.out.printf("峰值 QPS: %d (出现在第 %d 秒)%n", peakQps, peakSecond + 1);

        // 计算平均 QPS（排除首尾可能不完整的秒）
        if (seconds.size() >= 3) {
            int sumMiddle = 0;
            for (int i = 1; i < seconds.size() - 1; i++) {
                sumMiddle += qpsPerSecond.get(seconds.get(i)).get();
            }
            double avgQps = sumMiddle * 1.0 / (seconds.size() - 2);
            System.out.printf("平均 QPS (排除首尾): %.2f%n", avgQps);
        }
        System.out.println("============================================================");
    }

    private String doSeckill(String token, Long productId) {
        String url = SECKILL_URL + "?id=" + productId;

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
        errorCount.set(0);
        responseTimes.clear();
    }

    private void printStatistics(long totalTime) {
        System.out.println("\n====================== 测试统计结果 ======================");
        System.out.println("总请求数: " + tokens.size());
        System.out.println("成功数: " + successCount.get());
        System.out.println("失败数: " + failCount.get());
        System.out.println("重复购买数: " + duplicateCount.get());
        System.out.println("售罄数: " + soldOutCount.get());
        System.out.println("错误数: " + errorCount.get());
        System.out.println("成功率: " + String.format("%.2f%%", successCount.get() * 100.0 / tokens.size()));
        System.out.println("总耗时: " + totalTime + "ms");
        System.out.println("QPS: " + String.format("%.2f", tokens.size() * 1000.0 / totalTime));

        if (!responseTimes.isEmpty()) {
            Collections.sort(responseTimes);
            long minTime = responseTimes.get(0);
            long maxTime = responseTimes.get(responseTimes.size() - 1);
            long avgTime = responseTimes.stream().mapToLong(Long::longValue).sum() / responseTimes.size();
            long medianTime = responseTimes.get(responseTimes.size() / 2);
            long p95Time = responseTimes.get((int) (responseTimes.size() * 0.95));
            long p99Time = responseTimes.get((int) (responseTimes.size() * 0.99));

            System.out.println("\n响应时间统计:");
            System.out.println("最小响应时间: " + minTime + "ms");
            System.out.println("最大响应时间: " + maxTime + "ms");
            System.out.println("平均响应时间: " + avgTime + "ms");
            System.out.println("中位数响应时间: " + medianTime + "ms");
            System.out.println("P95响应时间: " + p95Time + "ms");
            System.out.println("P99响应时间: " + p99Time + "ms");
        }
        System.out.println("============================================================");
    }

    private String extractToken(String jsonStr) {
        try {
            JSONObject json = JSON.parseObject(jsonStr);

            if (json.containsKey("data") && json.get("data") instanceof JSONObject) {
                JSONObject data = json.getJSONObject("data");
                if (data.containsKey("token")) {
                    return data.getString("token");
                }
            }

            if (json.containsKey("token")) {
                return json.getString("token");
            }

            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                return jsonStr.replaceAll("\"", "");
            }

        } catch (Exception e) {
            System.err.println("JSON 解析失败: " + jsonStr);
        }
        return null;
    }
}
