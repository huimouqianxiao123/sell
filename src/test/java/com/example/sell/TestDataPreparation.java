package com.example.sell;

import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 测试数据准备工具类
 * 用于批量创建测试用户
 * 
 * @author 屈轩
 */
@Slf4j
@EnabledIfSystemProperty(named = "manualTest", matches = "true")
public class TestDataPreparation {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String REGISTER_URL = BASE_URL + "/auth/register";
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 批量创建测试用户（1001-1020）
     */
    @Test
    public void createTestUsers() {
        log.info("========================================");
        log.info("开始批量创建测试用户");
        log.info("========================================");
        
        int successCount = 0;
        int failCount = 0;
        
        // 创建 1001-1020 共20个用户
        for (int i = 1; i <= 20; i++) {
            String username = "100" + i;
            String password = "123456";
            String role = "user";
            
            boolean success = registerUser(username, password, role);
            
            if (success) {
                successCount++;
                log.info("✅ 用户 {} 创建成功", username);
            } else {
                failCount++;
                log.warn("❌ 用户 {} 创建失败（可能已存在）", username);
            }
            
            // 避免请求过快
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("========================================");
        log.info("批量创建测试用户完成");
        log.info("成功: {} 个", successCount);
        log.info("失败: {} 个", failCount);
        log.info("========================================");
    }

    /**
     * 创建单个测试用户
     */
    @Test
    public void createSingleTestUser() {
        String username = "1001";
        String password = "123456";
        String role = "user";
        
        log.info("创建测试用户: {}", username);
        
        boolean success = registerUser(username, password, role);
        
        if (success) {
            log.info("✅ 用户 {} 创建成功", username);
        } else {
            log.error("❌ 用户 {} 创建失败", username);
        }
    }

    /**
     * 注册用户
     * 
     * @param username 用户名
     * @param password 密码
     * @param role 角色
     * @return 是否成功
     */
    private boolean registerUser(String username, String password, String role) {
        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.set("username", username);
            requestBody.set("password", password);
            requestBody.set("role", role);
            
            RequestBody body = RequestBody.create(
                    requestBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(REGISTER_URL)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();
            
            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    log.debug("注册响应: {}", responseBody);
                    
                    // 检查响应
                    JSONObject jsonResponse = cn.hutool.json.JSONUtil.parseObj(responseBody);
                    Integer code = jsonResponse.getInt("code");
                    
                    return code != null && code == 200;
                }
            }
        } catch (IOException e) {
            log.error("注册用户异常: {}", e.getMessage());
        }
        
        return false;
    }

    /**
     * 验证测试用户是否可以登录
     */
    @Test
    public void verifyTestUsers() {
        log.info("========================================");
        log.info("验证测试用户登录");
        log.info("========================================");
        
        String loginUrl = BASE_URL + "/auth/login";
        int successCount = 0;
        int failCount = 0;
        
        for (int i = 1; i <= 20; i++) {
            String username = "100" + i;
            String password = "123456";
            String role = "user";
            
            try {
                // 构建登录请求
                JSONObject requestBody = new JSONObject();
                requestBody.set("username", username);
                requestBody.set("password", password);
                requestBody.set("role", role);
                
                RequestBody body = RequestBody.create(
                        requestBody.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );
                
                Request request = new Request.Builder()
                        .url(loginUrl)
                        .post(body)
                        .build();
                
                // 发送请求
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = cn.hutool.json.JSONUtil.parseObj(responseBody);
                        
                        Integer code = jsonResponse.getInt("code");
                        if (code != null && code == 200) {
                            String token = jsonResponse.getJSONObject("data").getStr("token");
                            log.info("✅ 用户 {} 登录成功, token前10位: {}", username, 
                                    token != null && token.length() > 10 ? token.substring(0, 10) + "..." : "无");
                            successCount++;
                        } else {
                            log.warn("❌ 用户 {} 登录失败: {}", username, jsonResponse.getStr("message"));
                            failCount++;
                        }
                    }
                }
                
                Thread.sleep(50);
                
            } catch (Exception e) {
                log.error("验证用户 {} 登录时异常: {}", username, e.getMessage());
                failCount++;
            }
        }
        
        log.info("========================================");
        log.info("验证完成");
        log.info("登录成功: {} 个", successCount);
        log.info("登录失败: {} 个", failCount);
        log.info("========================================");
    }

    /**
     * 打印测试用户信息（用于复制）
     */
    @Test
    public void printTestUserInfo() {
        log.info("========================================");
        log.info("测试用户信息");
        log.info("========================================");
        log.info("用户名范围: 1001 - 1020");
        log.info("密码: 123456");
        log.info("角色: user");
        log.info("========================================");
        log.info("用户列表:");
        
        for (int i = 1; i <= 20; i++) {
            log.info("用户名: 100{}, 密码: 123456, 角色: user", i);
        }
        
        log.info("========================================");
    }

    /**
     * 生成批量插入用户的SQL（需要手动加密密码）
     */
    @Test
    public void generateInsertSQL() {
        log.info("========================================");
        log.info("生成批量插入用户的SQL");
        log.info("========================================");
        log.info("注意: 密码需要使用BCrypt加密，这里只是模板");
        log.info("========================================");
        
        StringBuilder sql = new StringBuilder();
        sql.append("-- 批量插入测试用户 (密码需要BCrypt加密)\n");
        sql.append("INSERT INTO user (username, password, role) VALUES\n");
        
        for (int i = 1; i <= 20; i++) {
            String username = "100" + i;
            sql.append(String.format("('%s', '$2a$10$替换为加密后的密码', 'user')", username));
            
            if (i < 20) {
                sql.append(",\n");
            } else {
                sql.append(";\n");
            }
        }
        
        log.info("\n{}", sql.toString());
        log.info("========================================");
    }
}
