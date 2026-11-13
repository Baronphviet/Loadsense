package com.loadsense.core.service;

import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.UserAccount;
import com.loadsense.core.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final String dummyHash;
    private final RateLimiter loginFailures = new RateLimiter(5, 60);
    public AuthService(UserAccountRepository users, PasswordEncoder passwords, JwtEncoder jwt) {
        this.users=users; this.passwords=passwords; this.jwt=jwt;
        this.dummyHash=passwords.encode("invalid-account-password");
    }
    public AuthResponse register(Credentials input) {
        validatePassword(input.password());
        if(users.findByUsername(input.username()).isPresent()) throw ApiException.conflict("Tên đăng nhập đã tồn tại");
        var user=new UserAccount(); user.username=input.username(); user.passwordHash=passwords.encode(input.password());
        return token(users.saveAndFlush(user));
    }
    public AuthResponse login(Credentials input) {
        validatePassword(input.password());
        String key=input.username().toLowerCase(java.util.Locale.ROOT);
        loginFailures.checkLimitedOnly(key, "LOGIN_RATE_LIMITED", "Đăng nhập sai quá nhiều lần; hãy thử lại sau 1 phút");
        var user=users.findByUsername(input.username()).orElse(null);
        boolean matches=passwords.matches(input.password(),user==null ? dummyHash : user.passwordHash);
        if(user==null || !matches) {
            loginFailures.checkOrThrow(key, "LOGIN_RATE_LIMITED", "Đăng nhập sai quá nhiều lần; hãy thử lại sau 1 phút");
            throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Tên đăng nhập hoặc mật khẩu không đúng");
        }
        loginFailures.reset(key);
        return token(user);
    }
    private void validatePassword(String password) {
        if(password.getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.invalid("Mật khẩu tối đa 72 byte UTF-8");
    }
    private AuthResponse token(UserAccount user) {
        var now=Instant.now(); var expiry=now.plusSeconds(86400);
        var claims=JwtClaimsSet.builder().issuer("loadsense").subject(user.id.toString()).issuedAt(now)
            .expiresAt(expiry).claim("username",user.username).build();
        var encoded=jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims));
        return new AuthResponse(encoded.getTokenValue(),user.username,user.id,expiry);
    }
    public UserSettingsDto getSettings(UUID userId) {
        var user=users.findById(userId).orElseThrow(ApiException::missing);
        return toSettingsDto(user);
    }
    public UserSettingsDto updateSettings(UUID userId, UserSettingsDto input) {
        var user=users.findById(userId).orElseThrow(ApiException::missing);
        user.autoAiAnalyze=input.autoAiAnalyze();
        user.earlyAnomalyEnabled=input.earlyAnomalyEnabled();
        user.anomalyErrorRateThreshold=input.anomalyErrorRateThreshold();
        user.anomalyP95ThresholdMs=input.anomalyP95ThresholdMs();
        user.autoStopOnAnomaly=input.autoStopOnAnomaly();
        return toSettingsDto(users.save(user));
    }
    private UserSettingsDto toSettingsDto(UserAccount user) {
        return new UserSettingsDto(user.autoAiAnalyze,user.earlyAnomalyEnabled,
            user.anomalyErrorRateThreshold,user.anomalyP95ThresholdMs,user.autoStopOnAnomaly);
    }
}
