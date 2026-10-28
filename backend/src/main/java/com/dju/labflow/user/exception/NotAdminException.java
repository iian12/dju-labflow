package com.dju.labflow.user.exception;

/** 관리자가 아닌 사용자의 관리자 권한 회수 시도를 나타낸다. */
public class NotAdminException extends UserException {
    public NotAdminException() {
        super("Not admin");
    }
}
