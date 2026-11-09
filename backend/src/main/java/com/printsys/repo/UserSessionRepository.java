package com.printsys.repo;

import com.printsys.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    @Modifying
    @Query("delete from UserSession s where s.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from UserSession s where s.subjectType = :type and s.subjectId = :id")
    int deleteBySubject(@Param("type") String type, @Param("id") Long id);

    @Modifying
    @Query("update UserSession s set s.lastSeenAt = :now where s.tokenHash = :hash")
    int touch(@Param("hash") String hash, @Param("now") LocalDateTime now);
}
