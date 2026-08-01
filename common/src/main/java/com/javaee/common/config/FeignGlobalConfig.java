package com.javaee.common.config;

import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

/**
 * @author dqh
 * @description: Feign全局配置（超时/拦截）
 */
@Configuration
public class FeignGlobalConfig {

    /**
     * 配置Feign日志级别
     * @return Logger实例
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }

    /**
     * 配置Feign超时时间
     * @return Request.Options实例
     */
    @Bean
    public Request.Options feignOptions() {
        return new Request.Options(10, TimeUnit.SECONDS, 60, TimeUnit.SECONDS, true);
    }

    /**
     * 配置Feign重试策略
     * @return Retryer实例
     */
    @Bean
    public Retryer feignRetryer() {
        return new Retryer.Default(100, 1000, 3);
    }

    /**
     * Feign身份透传拦截器：
     * 服务间调用（如 document → file）不经过网关，默认不带任何身份头。
     * 此拦截器在每次Feign请求发出前：
     * 1. 若当前线程存在HTTP请求上下文（用户请求触发的调用），把 Authorization / X-User-Id / X-Username / X-Role 原样复制过去，
     *    让用户身份沿 前端 → 网关 → 服务A → (Feign) → 服务B 一路传递；
     * 2. 若没有HTTP上下文（如MQ监听器、定时任务线程发起的调用），注入内部服务身份兜底，
     *    避免服务间自动化流程因匿名被拒。
     * 注意：X-User-Id 必须是数字（下游 JwtAuthenticationFilter.trustGatewayHeaders 会 Long.valueOf 解析）。
     */
    @Bean
    public RequestInterceptor feignAuthPropagationInterceptor() {
        return template -> {
            String[] headersToPropagate = {"Authorization", "X-User-Id", "X-Username", "X-Role"};
            boolean propagated = false;

            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                for (String header : headersToPropagate) {
                    String value = request.getHeader(header);
                    if (value != null && !value.isEmpty()) {
                        template.header(header, value);
                        propagated = true;
                    }
                }
            }

            // 无用户上下文时（MQ/定时任务线程），以内部服务身份调用
            if (!propagated) {
                template.header("X-User-Id", "0");
                template.header("X-Username", "internal-service");
                template.header("X-Role", "ADMIN");
            }
        };
    }
}
