package com.skala.queue_server.config;

import com.skala.queue_server.filter.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter() {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new JwtAuthenticationFilter());
        reg.addUrlPatterns("/queue/*");
        reg.setOrder(2);
        return reg;
    }
}
