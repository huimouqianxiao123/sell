package com.example.sell.config;

import com.example.sell.Inceptor.LoginInceptor;
import com.example.sell.Inceptor.RateLimitInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author 屈轩
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Resource
    private LoginInceptor loginInceptor;

    @Resource
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/startSeckill", "/api/result", "/api/miaoshalist")
                .order(0);

        registry.addInterceptor(loginInceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/register",
                        "/knowledge/upload",
                "/ai/**",
                "/ai-test.html",
                        "/alipay/notify",
                        "/alipay/return",
                        "/alipay/mock-pay"
                )
                .order(1);
        WebMvcConfigurer.super.addInterceptors(registry);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/ai/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

}
