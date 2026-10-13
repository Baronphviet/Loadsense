package com.loadsense.core.service;
import org.springframework.http.HttpStatus;
public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Không tìm thấy dữ liệu"); }
    public static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"CONFLICT",message); }
}
