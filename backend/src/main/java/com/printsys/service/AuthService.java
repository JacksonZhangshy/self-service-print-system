package com.printsys.service;

import com.printsys.auth.AuthPrincipal;
import com.printsys.auth.SessionService;
import com.printsys.config.PrintSysProperties;
import com.printsys.entity.AdminUser;
import com.printsys.entity.User;
import com.printsys.repo.AdminUserRepository;
import com.printsys.repo.UserRepository;
import com.printsys.util.BizException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 登录：用户走手机号+验证码，管理员走账号+BCrypt 口令，登录成功签发服务端会话。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final SmsService smsService;
    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final PrintSysProperties props;

    /** 首次启动用配置项初始化管理员（口令 BCrypt 存储），之后以库为准 */
    @PostConstruct
    @Transactional
    public void initAdmin() {
        PrintSysProperties.Admin cfg = props.getAuth().getAdmin();
        adminUserRepository.findByUsername(cfg.getUsername()).orElseGet(() -> {
            AdminUser admin = new AdminUser();
            admin.setUsername(cfg.getUsername());
            admin.setPasswordHash(passwordEncoder.encode(cfg.getPassword()));
            log.info("初始化管理员账号 {}", cfg.getUsername());
            if (cfg.getPassword() == null || cfg.getPassword().length() < 8) {
                log.warn("管理员初始口令过短，请登录后立即在管理端修改");
            }
            return adminUserRepository.save(admin);
        });
    }

    @Transactional
    public String loginByPhone(String phone, String code, String clientIp) {
        smsService.verify(phone, code);
        User user = userRepository.findByPhone(phone).orElseGet(() -> {
            User u = new User();
            u.setPhone(phone);
            u.setRole("USER");
            return userRepository.save(u);
        });
        if ("BLOCKED".equals(user.getStatus())) {
            throw new BizException(403, "该账号已被停用，请联系工作人员");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        // 公共电脑：同一账号只保留一个会话，旧会话自动失效
        sessionService.revokeAllOf(SessionService.TYPE_USER, user.getId());
        return sessionService.issue(SessionService.TYPE_USER, user.getId(), user.getPhone(), clientIp);
    }

    @Transactional
    public String loginAdmin(String username, String password, String clientIp) {
        AdminUser admin = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(401, "账号或密码不正确"));
        if (!admin.isEnabled() || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new BizException(401, "账号或密码不正确");
        }
        admin.setLastLoginAt(LocalDateTime.now());
        adminUserRepository.save(admin);
        return sessionService.issue(SessionService.TYPE_ADMIN, admin.getId(), admin.getUsername(), clientIp);
    }

    public void logout(String token) {
        sessionService.revoke(token);
    }

    @Transactional
    public void changeAdminPassword(AuthPrincipal principal, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new BizException("新口令至少 8 位");
        }
        AdminUser admin = adminUserRepository.findById(principal.getSubjectId())
                .orElseThrow(() -> new BizException(404, "管理员不存在"));
        if (!passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new BizException(401, "原口令不正确");
        }
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        adminUserRepository.save(admin);
        sessionService.revokeAllOf(SessionService.TYPE_ADMIN, admin.getId());
    }
}
