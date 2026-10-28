package com.dju.labflow.user.exception;

/** 사용자 도메인 조회 실패. 비밀번호 인증 경계에서는 별도의 Security 예외로 변환한다. */
public class UserNotFoundException extends UserException {
    public UserNotFoundException() {
        super("User not found");
    }
}
