package com.example.sell.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Lua 脚本内容持有器（供 Redisson RScript 使用）
 * <p>
 * 启动时从 classpath 加载所有 Lua 脚本内容，避免每次执行时重复读取文件。
 * 配合 Redisson 的 {@code RScript.eval()} 使用，替代 Spring 的 {@code DefaultRedisScript}。
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Getter
@Component
public class LuaScripts {

    private String seckill;
    private String warmUp;
    private String rollback;
    private String rateLimiter;

    @PostConstruct
    public void init() throws IOException {
        seckill = loadScript("lua/seckill.lua");
        warmUp = loadScript("lua/seckill_warmup.lua");
        rollback = loadScript("lua/seckill_rollback.lua");
        rateLimiter = loadScript("lua/rate_limiter.lua");
        log.info("【Lua脚本】所有Lua脚本加载完成");
    }

    private String loadScript(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
