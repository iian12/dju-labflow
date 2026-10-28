package com.dju.labflow.global.config;

import com.dju.labflow.global.resolver.CurrentUserIdResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 현재 사용자 ID 리졸버를 MVC에 연결해 요청값 대신 인증 정보를 주입한다. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserIdResolver currentUserIdResolver;

    public WebMvcConfig(CurrentUserIdResolver currentUserIdResolver) {
        this.currentUserIdResolver = currentUserIdResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdResolver);
    }
}
