package com.printsys.repo;

import com.printsys.entity.SmsCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SmsCodeRepository extends JpaRepository<SmsCode, Long> {

    Optional<SmsCode> findFirstByPhoneAndPurposeOrderByCreatedAtDesc(String phone, String purpose);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime after);

    long countByClientIpAndCreatedAtAfter(String clientIp, LocalDateTime after);

    @Modifying
    @Query("delete from SmsCode c where c.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
