package com.printsys.repo;

import com.printsys.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);

    org.springframework.data.domain.Page<User> findByPhoneContaining(String phone,
                                                                    org.springframework.data.domain.Pageable pageable);
}
