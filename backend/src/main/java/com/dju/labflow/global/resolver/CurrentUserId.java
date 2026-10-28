package com.dju.labflow.global.resolver;

import java.lang.annotation.*;

/** 컨트롤러 인자에 인증된 사용자 ID를 주입하도록 지정하는 애너테이션. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {
    boolean required() default true;
}
