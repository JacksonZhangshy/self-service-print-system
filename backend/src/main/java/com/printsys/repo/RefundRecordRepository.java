package com.printsys.repo;

import com.printsys.entity.RefundRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundRecordRepository extends JpaRepository<RefundRecord, Long> {
    List<RefundRecord> findByOrderNo(String orderNo);

    Page<RefundRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
