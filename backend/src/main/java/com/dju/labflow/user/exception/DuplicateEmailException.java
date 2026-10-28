package com.dju.labflow.user.exception;

/** 동일 이메일 회원가입을 HTTP 409로 구분하기 위한 도메인 예외. */
public class DuplicateEmailException extends UserException {
    public DuplicateEmailException() {
        super("Email already exists.");
    }
}
