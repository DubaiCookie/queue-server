package com.skala.queue_server.config;

import com.skala.queue_server.filter.JwtAuthenticationFilter;
import com.skala.queue_server.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class FilterConfig {
    // ㅎㅇ
    private final JwtUtil jwtUtil;

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter() {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new JwtAuthenticationFilter(jwtUtil));
        reg.addUrlPatterns("/queue/*");
        reg.setOrder(2);
        return reg;
    }
}
