package com.dju.labflow.global.resolver;

import com.dju.labflow.auth.infrastructure.security.AppPrincipal;
import com.dju.labflow.user.domain.UserId;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 요청 파라미터를 신뢰하지 않고 SecurityContext의 일반 사용자 인증 주체에서 ID를 추출한다. */
@Component
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class) &&
                UserId.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public @Nullable Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer, NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {
        CurrentUserId ann = parameter.getParameterAnnotation(CurrentUserId.class);
        boolean required = ann == null || ann.required();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            if (!required) return null;
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }

        Object principal = auth.getPrincipal();
        if (principal instanceof AppPrincipal ap) {
            return ap.userId();
        }

        throw new AuthenticationCredentialsNotFoundException("Authentication required");
    }
}
