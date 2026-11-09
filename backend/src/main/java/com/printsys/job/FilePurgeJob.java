package com.printsys.job;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.OrderStatus;
import com.printsys.entity.StoredFile;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.StoredFileRepository;
import com.printsys.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * 文件保留期清理（个人信息最小化）：超过保留天数且没有「在途订单」引用的文件从磁盘删除并标记 purged。
 * 只删文件，不删订单/流水，保证账目可查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilePurgeJob {
    private static final EnumSet<OrderStatus> ACTIVE_STATUSES = EnumSet.of(
            OrderStatus.CREATED, OrderStatus.PAYING, OrderStatus.PAID, OrderStatus.PRINTING);

    private final StoredFileRepository fileRepository;
    private final PrintOrderRepository orderRepository;
    private final FileService fileService;
    private final PrintSysProperties props;

    @Scheduled(cron = "0 30 4 * * ?")
    public void run() {
        int days = props.getFile().getRetentionDays();
        if (days <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<StoredFile> candidates = fileRepository.findTop200ByPurgedFalseAndCreatedAtBefore(cutoff);
        int purged = 0;
        for (StoredFile f : candidates) {
            try {
                if (orderRepository.existsByFileIdAndStatusIn(f.getId(), ACTIVE_STATUSES)) {
                    continue;
                }
                fileService.purge(f);
                purged++;
            } catch (Exception e) {
                log.warn("文件 {} 清理失败: {}", f.getId(), e.getMessage());
            }
        }
        if (purged > 0) {
            log.info("文件保留期清理完成，删除 {} 个文件（保留 {} 天）", purged, days);
        }
    }
}
