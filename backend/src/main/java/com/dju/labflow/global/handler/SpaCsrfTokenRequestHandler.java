package com.dju.labflow.global.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

public class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {


    // 응답 쪽에서는 BREACH 보호 적용
    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    // 요청 헤더로 전달된 실제 CSRF 토큰을 읽을 때 사용
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        delegate.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public @Nullable String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());

        if (headerValue != null && !headerValue.isBlank()) {
            return plain.resolveCsrfTokenValue(request, csrfToken);
        }

        return delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}
