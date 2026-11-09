package com.printsys.repo;

import com.printsys.entity.OrderStatus;
import com.printsys.entity.PrintOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PrintOrderRepository extends JpaRepository<PrintOrder, Long> {

    Optional<PrintOrder> findByOrderNo(String orderNo);

    /**
     * 一次性把 user / file 关联取出来。
     * 本项目关闭了 open-in-view，DTO 映射发生在事务之外，
     * 用懒加载代理会抛 LazyInitializationException，所以对外返回前必须 fetch join。
     */
    @Query("select o from PrintOrder o join fetch o.user join fetch o.file where o.orderNo = :orderNo")
    Optional<PrintOrder> findByOrderNoFetch(@Param("orderNo") String orderNo);

    /** 入账/退款必须串行：同一订单仅允许一个事务推进状态 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PrintOrder o where o.orderNo = :orderNo")
    Optional<PrintOrder> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user", "file"})
    Page<PrintOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user", "file"})
    Page<PrintOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user", "file"})
    Page<PrintOrder> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user", "file"})
    Page<PrintOrder> findByUserPhoneContainingOrderByCreatedAtDesc(String phone, Pageable pageable);

    @Query("select o from PrintOrder o where o.paidAt is not null and o.paidAt >= :from and o.paidAt < :to order by o.paidAt")
    List<PrintOrder> findPaidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select o from PrintOrder o where o.status in (com.printsys.entity.OrderStatus.CREATED, " +
            "com.printsys.entity.OrderStatus.PAYING) and o.expireAt < :now")
    List<PrintOrder> findExpired(@Param("now") LocalDateTime now, Pageable pageable);

    long countByStatus(OrderStatus status);

    /** 该文件是否仍被未完结订单引用（用于文件过期清理） */
    boolean existsByFileIdAndStatusIn(Long fileId, java.util.Collection<OrderStatus> statuses);

    /** 抢占退款：只有「未退过款」的订单能被置为退款中，返回 1 表示本次抢到 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PrintOrder o set o.refundStatus = com.printsys.entity.RefundStatus.PROCESSING, " +
            "o.status = com.printsys.entity.OrderStatus.REFUNDING, o.refundAmount = :amount, " +
            "o.refundNo = :refundNo where o.orderNo = :orderNo " +
            "and o.refundStatus = com.printsys.entity.RefundStatus.NONE")
    int claimRefund(@Param("orderNo") String orderNo,
                    @Param("amount") java.math.BigDecimal amount,
                    @Param("refundNo") String refundNo);
}
