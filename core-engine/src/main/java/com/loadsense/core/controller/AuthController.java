package com.loadsense.core.controller;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    AuthResponse register(@Valid @RequestBody Credentials input) { return auth.register(input); }
    @PostMapping("/login") AuthResponse login(@Valid @RequestBody Credentials input) { return auth.login(input); }
}
