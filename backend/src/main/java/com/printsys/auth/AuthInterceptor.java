package com.printsys.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printsys.config.PrintSysProperties;
import com.printsys.util.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 鉴权拦截：
 * - /api/agent/** ：X-Agent-Token 共享密钥（常量时间比较）；
 * - /api/admin/** ：管理员会话（/api/admin/login 放行）；
 * - 其余 /api/**  ：用户会话（登录、支付回调、模拟收银台等公开路径在 WebMvcConfig 放行）。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR_PRINCIPAL = "authPrincipal";
    public static final String ATTR_TOKEN = "authToken";

    private final SessionService sessionService;
    private final PrintSysProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        if (path.startsWith("/api/agent/")) {
            String agentToken = request.getHeader("X-Agent-Token");
            if (!constantTimeEquals(props.getAgent().getToken(), agentToken)) {
                return reject(response, 401, "打印助手令牌无效");
            }
            return true;
        }

        String token = bearer(request);
        AuthPrincipal principal = sessionService.resolve(token);
        if (principal == null) {
            return reject(response, 401, "未登录或登录已过期");
        }
        if (path.startsWith("/api/admin/") && !principal.isAdmin()) {
            return reject(response, 403, "需要管理员权限");
        }
        request.setAttribute(ATTR_PRINCIPAL, principal);
        request.setAttribute(ATTR_TOKEN, token);
        return true;
    }

    public static String bearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;
    }

    public static AuthPrincipal current(HttpServletRequest request) {
        return (AuthPrincipal) request.getAttribute(ATTR_PRINCIPAL);
    }

    public static String currentToken(HttpServletRequest request) {
        return (String) request.getAttribute(ATTR_TOKEN);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean reject(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, msg)));
        return false;
    }
}
