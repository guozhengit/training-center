package com.guoyongzheng.training.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * API 跨域配置。
 *
 * <p>允许的来源通过 {@code training.cors.allowed-origins} 配置（逗号分隔，支持 {@code *} 通配，
 * 例如 {@code http://192.168.*.*:5173}）。默认值覆盖本地开发（vite dev server）。
 *
 * <p>部署到域名/反向代理后<b>必须</b>用环境变量 {@code TRAINING_CORS_ALLOWED_ORIGINS} 加上实际访问来源，
 * 否则浏览器发出的非 GET 请求（带 Origin 头）会被判为跨域并返回 403。
 * 注意：同域的 GET 请求浏览器不带 Origin 头（看起来正常），只有 POST/PUT/DELETE 才会 403，
 * 所以这个问题很容易被误解成"接口坏了"。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Value("${training.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://192.168.*.*:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
