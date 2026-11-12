package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DeliveryConfig {

    @Bean
    RetryPolicy retryPolicy(AppProperties properties) {
        return new RetryPolicy(properties.delivery().retry());
    }
}
