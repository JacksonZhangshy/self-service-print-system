package com.printsys.util;

import jakarta.servlet.http.HttpServletRequest;

/** 取真实客户端 IP（部署在 Nginx 之后取 X-Forwarded-For 第一段）。 */
public final class ClientIp {
    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.length() > 45 ? real.substring(0, 45) : real;
        }
        String remote = request.getRemoteAddr();
        return remote != null && remote.length() > 45 ? remote.substring(0, 45) : remote;
    }
}
