package com.model_shared.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class Resilience4jCircuitBreakerConfig {

    @Bean
    @ConditionalOnMissingBean(name = "securityServiceCircuitBreakerConfig")
    public CircuitBreakerConfig securityServiceCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                // Sliding window: Tính failure rate dựa trên 10 requests gần nhất
                .slidingWindowSize(10)
                
                // Phải có ít nhất 5 calls mới tính failure rate (tránh false positive khi có ít requests)
                .minimumNumberOfCalls(5)
                
                // Cho phép 3 calls trong HALF_OPEN state để test service đã recover chưa
                .permittedNumberOfCallsInHalfOpenState(3)
                
                // Tự động chuyển từ OPEN sang HALF_OPEN sau waitDurationInOpenState
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                
                // Đợi 60s trước khi chuyển từ OPEN sang HALF_OPEN (thử lại)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                
                // 50% failures trong sliding window → Circuit chuyển sang OPEN
                .failureRateThreshold(50f)
                
                // 100% slow calls → Circuit chuyển sang OPEN
                .slowCallRateThreshold(100f)
                
                // Call > 2s được tính là slow call
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                
                // Record exceptions được tính là failures
                .recordExceptions(
                    java.net.ConnectException.class,
                    java.net.SocketTimeoutException.class,
                    java.util.concurrent.TimeoutException.class,
                    org.springframework.web.client.ResourceAccessException.class,
                    org.springframework.web.client.HttpServerErrorException.class
                )
                
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = securityServiceCircuitBreakerConfig();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);
        // Register với tên "security-service"
        registry.circuitBreaker("security-service", defaultConfig);
        return registry;
    }
}
