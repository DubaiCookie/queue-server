package com.skala.queue_server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Value("${spring.redis.sentinel.master:mymaster}")
    private String masterName;

    @Value("${spring.redis.sentinel.nodes:redis-sentinel:26379}")
    private String sentinelNodes;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration();
        sentinelConfig.master(masterName);
        for (String node : sentinelNodes.split(",")) {
            String[] parts = node.trim().split(":");
            sentinelConfig.sentinel(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }
        return new LettuceConnectionFactory(sentinelConfig);
    }

    @Bean
    public RedisTemplate<String, ?> redisTemplate() {
        RedisTemplate<String, ?> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory());
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        return redisTemplate;
    }
}
