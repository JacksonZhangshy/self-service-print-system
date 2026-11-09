package com.printsys.repo;

import com.printsys.entity.PrintTask;
import com.printsys.entity.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PrintTaskRepository extends JpaRepository<PrintTask, Long> {

    Optional<PrintTask> findByOrderId(Long orderId);

    /** 任务 + 订单 + 文件一次性取出（DTO 映射在事务外进行） */
    @Query("select t from PrintTask t join fetch t.order o join fetch o.file where t.id = :id")
    Optional<PrintTask> findByIdFetch(@Param("id") Long id);

    Optional<PrintTask> findFirstByStatusOrderByCreatedAtAsc(TaskStatus status);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"order", "order.user", "order.file"})
    Page<PrintTask> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"order", "order.user", "order.file"})
    Page<PrintTask> findByStatusOrderByCreatedAtDesc(TaskStatus status, Pageable pageable);

    /** 候选任务：本终端专属任务 + 未绑定终端的公共任务 */
    @Query("select t from PrintTask t where t.status = com.printsys.entity.TaskStatus.PENDING " +
            "and (:terminalId is null or t.terminalId is null or t.terminalId = :terminalId) " +
            "order by t.createdAt asc")
    List<PrintTask> findClaimCandidates(@Param("terminalId") String terminalId, Pageable pageable);

    /**
     * 原子领取：只有当前仍为 PENDING 的任务才会被置为 PRINTING。
     * 返回值 = 1 表示领取成功，0 表示被别人抢先领走。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PrintTask t set t.status = com.printsys.entity.TaskStatus.PRINTING, " +
            "t.agentId = :agentId, t.claimedAt = :now, t.deadlineAt = :deadline, t.failReason = null " +
            "where t.id = :id and t.status = com.printsys.entity.TaskStatus.PENDING")
    int tryClaim(@Param("id") Long id,
                 @Param("agentId") String agentId,
                 @Param("now") LocalDateTime now,
                 @Param("deadline") LocalDateTime deadline);

    /** 超时回收：PRINTING 且已过 deadline 的任务回到 PENDING */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PrintTask t set t.status = com.printsys.entity.TaskStatus.PENDING, " +
            "t.agentId = null, t.claimedAt = null, t.deadlineAt = null, t.retryCount = t.retryCount + 1, " +
            "t.failReason = :reason where t.id = :id and t.status = com.printsys.entity.TaskStatus.PRINTING")
    int requeue(@Param("id") Long id, @Param("reason") String reason);

    @Query("select t from PrintTask t where t.status = com.printsys.entity.TaskStatus.PRINTING " +
            "and t.deadlineAt is not null and t.deadlineAt < :now")
    List<PrintTask> findTimedOut(@Param("now") LocalDateTime now, Pageable pageable);

    /** 重试超限：直接把任务判为失败（仅当仍处于 PRINTING） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PrintTask t set t.status = com.printsys.entity.TaskStatus.FAILED, " +
            "t.failReason = :reason, t.finishedAt = :now " +
            "where t.id = :id and t.status = com.printsys.entity.TaskStatus.PRINTING")
    int markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);
}
