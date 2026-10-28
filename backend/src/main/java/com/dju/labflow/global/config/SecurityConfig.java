package com.dju.labflow.global.config;

import com.dju.labflow.auth.infrastructure.cookie.AuthCookieProperties;
import com.dju.labflow.auth.infrastructure.persistence.JwtAuthenticationFilter;
import com.dju.labflow.global.handler.CustomAccessDeniedHandler;
import com.dju.labflow.global.handler.CustomAuthenticationEntryPoint;
import com.dju.labflow.global.handler.SpaCsrfTokenRequestHandler;
import com.dju.labflow.project.key.infrastructure.security.ProjectApiKeyAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/** 쿠키 JWT와 SDK API 키 인증, CSRF, 공개 경로 및 보안 오류 응답을 구성한다. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;
    private final ProjectApiKeyAuthenticationFilter projectApiKeyAuthenticationFilter;
    private final AuthCookieProperties cookieProperties;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CustomAuthenticationEntryPoint authenticationEntryPoint,
            CustomAccessDeniedHandler customAccessDeniedHandler,
            ProjectApiKeyAuthenticationFilter projectApiKeyAuthenticationFilter,
            AuthCookieProperties cookieProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
        this.projectApiKeyAuthenticationFilter = projectApiKeyAuthenticationFilter;
        this.cookieProperties = cookieProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();

        /*
         * React가 읽는 쿠키 이름: XSRF-TOKEN
         * React가 서버로 보내는 헤더: X-XSRF-TOKEN
         */
        csrfTokenRepository.setCookieName("XSRF-TOKEN");
        csrfTokenRepository.setHeaderName("X-XSRF-TOKEN");
        csrfTokenRepository.setCookiePath("/");
        csrfTokenRepository.setCookieCustomizer(
                cookie ->
                        cookie.secure(cookieProperties.secure())
                                .sameSite(cookieProperties.sameSite()));

        http.cors(Customizer.withDefaults())
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                                        // 매 요청시마다 CSRF 토큰 재발급 방지
                                        .sessionAuthenticationStrategy(
                                                new NullAuthenticatedSessionStrategy())
                                        .ignoringRequestMatchers(
                                                "/api/v1/auth/sign-up",
                                                "/api/v1/auth/login",
                                                "/api/v1/sdk/**"))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        exception ->
                                exception
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(customAccessDeniedHandler))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        // 에러 디스패치가 Security에 다시 막히는 것 방지
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        // CORS Preflight 요청
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/v3/api-docs.yaml")
                                        .permitAll()
                                        // 인증 관련 공개 API
                                        .requestMatchers(
                                                "/api/v1/auth/sign-up",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/refresh",
                                                "/api/v1/auth/logout",
                                                "/api/v1/auth/csrf")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/email-verifications/verify",
                                                "/api/v1/auth/email-verifications/resend")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        projectApiKeyAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 인증 필터는 SecurityFilterChain에서만 실행되도록 컨테이너 자동 등록을 끈다. */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration() {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ProjectApiKeyAuthenticationFilter> apiKeyFilterRegistration() {
        FilterRegistrationBean<ProjectApiKeyAuthenticationFilter> registration =
                new FilterRegistrationBean<>(projectApiKeyAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
