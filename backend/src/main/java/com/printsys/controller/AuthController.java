package com.printsys.controller;

import com.printsys.auth.AuthInterceptor;
import com.printsys.config.PrintSysProperties;
import com.printsys.dto.Requests;
import com.printsys.service.AuthService;
import com.printsys.service.SmsService;
import com.printsys.util.ApiResponse;
import com.printsys.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final SmsService smsService;
    private final PrintSysProperties props;

    @PostMapping("/send-code")
    public ApiResponse<Map<String, Object>> sendCode(@Valid @RequestBody Requests.SendCodeRequest req,
                                                     HttpServletRequest request) {
        SmsService.SendResult r = smsService.sendCode(req.phone(), ClientIp.of(request));
        return ApiResponse.ok(Map.of(
                "demoMode", r.demoMode(),
                "demoCode", r.demoCode() == null ? "" : r.demoCode(),
                "cooldownSeconds", r.cooldownSeconds()));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody Requests.LoginRequest req,
                                                 HttpServletRequest request) {
        String token = authService.loginByPhone(req.phone(), req.code(), ClientIp.of(request));
        return ApiResponse.ok(Map.of("token", token, "phone", req.phone()));
    }

    /** 公共电脑：退出即吊销服务端会话，token 立即失效 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(AuthInterceptor.bearer(request));
        return ApiResponse.ok();
    }

    /** 公共电脑空闲自动登出时前端可先查一次会话是否还有效 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        var p = AuthInterceptor.current(request);
        return ApiResponse.ok(Map.of(
                "name", p.getName() == null ? "" : p.getName(),
                "admin", p.isAdmin(),
                "demoMode", props.getAuth().isDemoMode(),
                "idleMinutes", props.getAuth().getIdleMinutes()));
    }
}
