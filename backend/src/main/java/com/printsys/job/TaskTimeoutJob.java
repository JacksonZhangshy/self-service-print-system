package com.printsys.job;

import com.printsys.service.PrintTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 回收「助手领取后失联」的僵尸任务，重试超限则判失败并自动退款。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTimeoutJob {
    private final PrintTaskService printTaskService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void run() {
        try {
            printTaskService.reclaimTimedOut();
        } catch (Exception e) {
            log.error("任务超时回收失败", e);
        }
    }
}
