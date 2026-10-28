package com.dju.labflow.user.exception;

public class UserNotFoundException extends UserException {
    public UserNotFoundException() {
        super("User not found");
    }
}
