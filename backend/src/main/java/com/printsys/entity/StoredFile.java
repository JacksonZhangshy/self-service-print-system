package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 上传的待打印文件。
 * storagePath 为原始上传件；printPath 为服务端转换后的打印件（PDF），
 * 计价页数取自 printPath，保证「收多少钱 = 打多少页」。
 */
@Getter
@Setter
@Entity
@Table(name = "stored_file", indexes = {@Index(name = "idx_file_user", columnList = "user_id")})
public class StoredFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 500)
    private String storagePath;

    /** 实际送给打印机的文件（Office 文档为转换后的 PDF） */
    @Column(length = 500)
    private String printPath;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(length = 16)
    private String extension;

    @Column(nullable = false)
    private int pageCount = 1;

    /** REAL = 由转换后 PDF 精确统计；ESTIMATE = 估算（转换服务不可用时的降级） */
    @Column(name = "page_source", length = 16, nullable = false)
    private String pageSource = "REAL";

    @Column(nullable = false)
    private boolean converted = false;

    @Column(nullable = false)
    private boolean purged = false;

    private LocalDateTime createdAt = LocalDateTime.now();
}
