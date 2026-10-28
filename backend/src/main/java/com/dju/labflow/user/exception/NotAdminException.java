package com.dju.labflow.user.exception;

public class NotAdminException extends UserException {
    public NotAdminException() {
        super("Not admin");
    }
}
