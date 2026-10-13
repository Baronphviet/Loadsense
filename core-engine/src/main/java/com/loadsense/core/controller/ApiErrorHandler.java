package com.loadsense.core.controller;
import com.loadsense.core.dto.ApiDtos.ErrorResponse;
import com.loadsense.core.service.ApiException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        return ResponseEntity.status(e.status).body(new ErrorResponse(e.code,e.getMessage(),Map.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        Map<String,String> fields=new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_ERROR","Kiểm tra lại cấu hình nhập vào",fields));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> malformed(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_INPUT","Dữ liệu hoặc JSON không hợp lệ",Map.of()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(new ErrorResponse("CONFLICT","Dữ liệu bị trùng hoặc đang được sử dụng",Map.of()));
    }
}
