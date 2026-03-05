package com.example.sell;

import okhttp3.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BatchRegisterTest {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String REGISTER_URL = "http://localhost:8080/auth/register";

    @Test
    public void testBatchRegister() {
        int startUserId = 1001;
        int userCount =20;
        String password = "123456";

        System.out.println("====================== 开始批量注册用户 ======================");
        System.out.println("起始用户ID: " + startUserId);
        System.out.println("注册数量: " + userCount);
        System.out.println("密码: " + password);
        System.out.println("============================================================");

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < userCount; i++) {
            final int userId = startUserId + i;
            final String username = String.valueOf(userId);

            executorService.submit(() -> {
                try {
                    String json = String.format(
                            "{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"USER\"}",
                            username, password
                    );

                    RequestBody requestBody = RequestBody.create(json, JSON_MEDIA_TYPE);
                    Request request = new Request.Builder()
                            .url(REGISTER_URL)
                            .post(requestBody)
                            .header("Content-Type", "application/json")
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String result = response.body().string();
                            JSONObject jsonObject = JSON.parseObject(result);

                            if (jsonObject.getInteger("code") == 200) {
                                successCount.incrementAndGet();
                                System.out.printf("✓ 用户 %s 注册成功%n", username);
                            } else {
                                String msg = jsonObject.getString("msg");
                                if (msg.contains("已存在")) {
                                    duplicateCount.incrementAndGet();
                                    System.out.printf("○ 用户 %s 已存在，跳过%n", username);
                                } else {
                                    failCount.incrementAndGet();
                                    System.out.printf("✗ 用户 %s 注册失败: %s%n", username, msg);
                                }
                            }
                        } else {
                            failCount.incrementAndGet();
                            System.out.printf("✗ 用户 %s HTTP错误: %d%n", username, response.code());
                        }
                    }
                } catch (IOException e) {
                    failCount.incrementAndGet();
                    System.err.println("✗ 用户 " + username + " 请求异常: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;

            printStatistics(userCount, successCount.get(), failCount.get(), duplicateCount.get(), totalTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }
    }

    @Test
    public void testSequentialRegister() {
        int startUserId = 1001;
        int userCount = 20;
        String password = "123456";

        System.out.println("====================== 开始顺序注册用户 ======================");
        System.out.println("起始用户ID: " + startUserId);
        System.out.println("注册数量: " + userCount);
        System.out.println("密码: " + password);
        System.out.println("============================================================");

        int successCount = 0;
        int failCount = 0;
        int duplicateCount = 0;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < userCount; i++) {
            int userId = startUserId + i;
            String username = String.valueOf(userId);

            try {
                String json = String.format(
                        "{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"USER\"}",
                        username, password
                );

                RequestBody requestBody = RequestBody.create(json, JSON_MEDIA_TYPE);
                Request request = new Request.Builder()
                        .url(REGISTER_URL)
                        .post(requestBody)
                        .header("Content-Type", "application/json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String result = response.body().string();
                        JSONObject jsonObject = JSON.parseObject(result);

                        if (jsonObject.getInteger("code") == 200) {
                            successCount++;
                            System.out.printf("✓ 用户 %s 注册成功%n", username);
                        } else {
                            String msg = jsonObject.getString("msg");
                            if (msg.contains("已存在")) {
                                duplicateCount++;
                                System.out.printf("○ 用户 %s 已存在，跳过%n", username);
                            } else {
                                failCount++;
                                System.out.printf("✗ 用户 %s 注册失败: %s%n", username, msg);
                            }
                        }
                    } else {
                        failCount++;
                        System.out.printf("✗ 用户 %s HTTP错误: %d%n", username, response.code());
                    }
                }
            } catch (IOException e) {
                failCount++;
                System.err.println("✗ 用户 " + username + " 请求异常: " + e.getMessage());
            }
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        printStatistics(userCount, successCount, failCount, duplicateCount, totalTime);
    }

    private void printStatistics(int totalCount, int successCount, int failCount, int duplicateCount, long totalTime) {
        System.out.println("\n====================== 注册统计结果 ======================");
        System.out.println("总用户数: " + totalCount);
        System.out.println("成功注册: " + successCount);
        System.out.println("注册失败: " + failCount);
        System.out.println("已存在用户: " + duplicateCount);
        System.out.println("总耗时: " + totalTime + "ms");
        System.out.println("平均耗时: " + (totalTime / totalCount) + "ms");
        System.out.println("成功率: " + String.format("%.2f%%", successCount * 100.0 / totalCount));
        System.out.println("============================================================");
    }
}
