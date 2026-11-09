package com.printsys.service;

import com.printsys.entity.AuditLog;
import com.printsys.repo.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 敏感操作审计（改价、退款、补打、封禁、改密）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repo;

    public void log(String actor, String action, String target, String detail) {
        log(actor, action, target, detail, null);
    }

    public void log(String actor, String action, String target, String detail, String ip) {
        try {
            AuditLog a = new AuditLog();
            a.setActor(actor);
            a.setAction(action);
            a.setTarget(target);
            a.setDetail(detail != null && detail.length() > 1000 ? detail.substring(0, 1000) : detail);
            a.setIp(ip);
            repo.save(a);
        } catch (Exception e) {
            // 审计写入失败不能影响业务主流程
            log.warn("审计日志写入失败 action={} target={}", action, target, e);
        }
    }
}
