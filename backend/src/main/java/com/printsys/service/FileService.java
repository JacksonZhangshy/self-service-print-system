package com.printsys.service;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.StoredFile;
import com.printsys.entity.User;
import com.printsys.repo.StoredFileRepository;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * 文件落盘 + 真实性校验 + 转换 + 页数统计。
 * 安全要点：扩展名白名单 + 文件头魔数校验（防改后缀上传）+ 大小/页数上限 + UUID 文件名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
            "png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final StoredFileRepository repo;
    private final PageCountService pageCountService;
    private final DocumentConverter converter;
    private final PrintSysProperties props;

    @Transactional
    public StoredFile store(User user, MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new BizException("请选择要打印的文件");
        }
        long maxBytes = props.getFile().getMaxMb() * 1024L * 1024L;
        if (upload.getSize() > maxBytes) {
            throw new BizException(413, "文件超过 " + props.getFile().getMaxMb() + "MB 上限");
        }
        String original = upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename();
        String ext = extensionOf(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException("不支持的文件类型，仅支持 Word/Excel/PPT/PDF/图片/TXT");
        }

        LocalDate now = LocalDate.now();
        Path dir = Paths.get(props.getStorageDir()).toAbsolutePath()
                .resolve(String.valueOf(now.getYear()))
                .resolve(String.format("%02d", now.getMonthValue()));
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(storedName);

        Path printPath = null;
        try {
            Files.createDirectories(dir);
            upload.transferTo(target.toFile());
            verifyMagic(target, ext);

            int pageCount;
            String pageSource;
            if (converter.needsConvert(ext) && props.getConvert().isEnabled()) {
                printPath = converter.convertToPdf(target, ext);
                pageCount = pageCountService.countPdfPages(printPath.toFile());
                pageSource = "REAL";
            } else if (converter.needsConvert(ext)) {
                if (!props.getConvert().isAllowEstimate()) {
                    throw new BizException(503, "当前未启用文档转换，请上传 PDF 或图片");
                }
                pageCount = pageCountService.estimate(target.toFile(), ext);
                pageSource = "ESTIMATE";
            } else {
                pageCount = "pdf".equals(ext) ? pageCountService.countPdfPages(target.toFile()) : 1;
                pageSource = "REAL";
            }
            int maxPages = props.getOrder().getMaxPages();
            if (pageCount > maxPages) {
                throw new BizException("文档共 " + pageCount + " 页，超过单次 " + maxPages + " 页上限，请拆分后打印");
            }

            StoredFile sf = new StoredFile();
            sf.setUser(user);
            sf.setOriginalName(original.length() > 200 ? original.substring(0, 200) : original);
            sf.setStoragePath(target.toString());
            sf.setPrintPath(printPath != null ? printPath.toString() : target.toString());
            sf.setSizeBytes(upload.getSize());
            sf.setExtension(ext);
            sf.setPageCount(pageCount);
            sf.setPageSource(pageSource);
            sf.setConverted(printPath != null);
            return repo.save(sf);
        } catch (BizException e) {
            cleanQuietly(target, printPath);
            throw e;
        } catch (IOException e) {
            cleanQuietly(target, printPath);
            throw new BizException(500, "文件保存失败，请重试");
        }
    }

    /** 送给打印机的文件（Office 文档为转换后的 PDF） */
    public File resolvePrintFile(StoredFile sf) {
        String path = sf.getPrintPath() != null ? sf.getPrintPath() : sf.getStoragePath();
        File f = new File(path);
        if (!f.exists()) {
            throw new BizException(404, "文件已过保留期被清理，请重新上传");
        }
        return f;
    }

    public File resolvePreviewFile(StoredFile sf) {
        return resolvePrintFile(sf);
    }

    @Transactional
    public void purge(StoredFile sf) {
        cleanQuietly(Paths.get(sf.getStoragePath()), sf.getPrintPath() == null ? null : Paths.get(sf.getPrintPath()));
        sf.setPurged(true);
        repo.save(sf);
    }

    private void cleanQuietly(Path a, Path b) {
        try {
            if (a != null) {
                Files.deleteIfExists(a);
            }
        } catch (IOException ignored) {
            // 清理失败不影响主流程，交由保留期任务下次重试
        }
        try {
            if (b != null && !b.equals(a)) {
                Files.deleteIfExists(b);
            }
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** 校验文件头，防止把 HTML/脚本改后缀当 PDF/图片上传 */
    private void verifyMagic(Path file, String ext) throws IOException {
        byte[] head = new byte[8];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(head);
        }
        if (read < 4) {
            throw new BizException("文件内容为空或过短，无法识别");
        }
        boolean ok = switch (ext) {
            case "pdf" -> startsWith(head, read, new int[]{0x25, 0x50, 0x44, 0x46});
            case "png" -> startsWith(head, read, new int[]{0x89, 0x50, 0x4E, 0x47});
            case "jpg", "jpeg" -> startsWith(head, read, new int[]{0xFF, 0xD8, 0xFF});
            case "gif" -> startsWith(head, read, new int[]{0x47, 0x49, 0x46, 0x38});
            case "bmp" -> startsWith(head, read, new int[]{0x42, 0x4D});
            case "webp" -> startsWith(head, read, new int[]{0x52, 0x49, 0x46, 0x46});
            case "docx", "xlsx", "pptx" -> startsWith(head, read, new int[]{0x50, 0x4B, 0x03, 0x04})
                    || startsWith(head, read, new int[]{0x50, 0x4B, 0x05, 0x06});
            case "doc", "xls", "ppt" -> startsWith(head, read, new int[]{0xD0, 0xCF, 0x11, 0xE0});
            case "txt" -> true;
            default -> false;
        };
        if (!ok) {
            throw new BizException("文件内容与扩展名不符，请上传正确的文件");
        }
    }

    private static boolean startsWith(byte[] head, int read, int[] magic) {
        if (read < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((head[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
