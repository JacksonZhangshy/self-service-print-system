package com.printsys.repo;

import com.printsys.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findTop200ByPurgedFalseAndCreatedAtBefore(java.time.LocalDateTime before);
}
