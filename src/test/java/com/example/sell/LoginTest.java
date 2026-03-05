package com.example.sell;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest
public class LoginTest {

    private static final int CONCURRENT_THREADS = 100;
    private static final int REQUESTS_PER_THREAD = 10;
    private static final String LOGIN_URL = "http://localhost:8080/auth/login";

    private static final String USERNAME = "1001";
    private static final String PASSWORD = "123456";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(100, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build();

    @Test
    public void testLogin() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalTime = new AtomicLong(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(CONCURRENT_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);

        long start = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                        long reqStart = System.currentTimeMillis();
                        boolean success = doLogin(threadId, j);
                        long cost = System.currentTimeMillis() - reqStart;
                        totalTime.addAndGet(cost);

                        if (success) successCount.incrementAndGet();
                        else failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        System.out.println("=== 并发登录测试开始 ===");
        System.out.println("线程数: " + CONCURRENT_THREADS + ", 每线程请求: " + REQUESTS_PER_THREAD);
        startLatch.countDown();

        endLatch.await();
        executor.shutdown();

        long totalCost = System.currentTimeMillis() - start;
        int totalRequests = CONCURRENT_THREADS * REQUESTS_PER_THREAD;

        System.out.println("\n=== 测试结果 ===");
        System.out.println("总请求: " + totalRequests);
        System.out.println("成功: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.println("总耗时: " + totalCost + "ms");
        System.out.println("QPS: " + (totalRequests * 1000 / totalCost));
        System.out.println("平均响应: " + (totalRequests > 0 ? totalTime.get() / totalRequests : 0) + "ms");
    }

    private boolean doLogin(int threadId, int reqId) {
        try {
            // 方式1：简单字符串拼接（适合简单JSON）
            String json = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", USERNAME, PASSWORD);

            // 方式2：如果字段复杂，可用 Map + FastJSON/Gson（需添加依赖）
            /*
            Map<String, String> map = new HashMap<>();
            map.put("username", USERNAME);
            map.put("password", PASSWORD);
            String json = JSON.toJSONString(map);
            */

            RequestBody requestBody = RequestBody.create(
                    json,
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(LOGIN_URL)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String result = response.body() != null ? response.body().string() : "";

                // 根据实际返回调整判断逻辑
                boolean isSuccess = response.isSuccessful() &&
                        (result.contains("success") || result.contains("token") || result.contains("code\":200"));

                if (reqId == 0 && threadId < 3) { // 仅打印前3个线程的首次请求
                    System.out.printf("Thread-%d: HTTP=%d, Result=%s%n",
                            threadId, response.code(),
                            result.length() > 80 ? result.substring(0, 80) + "..." : result);
                }
                return isSuccess;
            }
        } catch (Exception e) {
            System.err.printf("Thread-%d Error: %s%n", threadId, e.getMessage());
            return false;
        }
    }
}