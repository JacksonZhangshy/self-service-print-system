package com.printsys.config;

import com.printsys.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/send-code",
                        "/api/auth/login",
                        "/api/auth/logout",
                        "/api/admin/login",
                        "/api/pay/alipay/notify",   // 渠道服务器回调，靠验签鉴权
                        "/api/pay/wechat/notify",
                        "/api/pay/mock/confirm",    // 仅 MOCK 渠道 + MOCK 订单可用，内部逐单校验
                        "/api/health"
                );
    }
}
