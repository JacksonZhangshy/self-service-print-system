package com.printsys.service;

import com.printsys.config.PrintSysProperties;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 服务端文档转换（LibreOffice 无界面）：把 Word/Excel/PPT/TXT 统一转成 PDF。
 * 转换的意义不只是「能打印」，而是让**计价页数 = 实际打印页数**：
 * 之前按估算页数收费、实际打印页数由打印机决定，会持续产生多收或少收。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentConverter {

    private final PrintSysProperties props;

    /** 该扩展名是否需要转换 */
    public boolean needsConvert(String ext) {
        return switch (ext.toLowerCase()) {
            case "pdf", "png", "jpg", "jpeg", "gif", "bmp", "webp" -> false;
            default -> true;
        };
    }

    /**
     * 转成 PDF 并返回产物路径；失败抛 BizException（上层决定是否降级）。
     */
    public Path convertToPdf(Path source, String ext) {
        PrintSysProperties.Convert cfg = props.getConvert();
        String soffice = findSoffice(cfg.getSofficePath());
        if (soffice == null) {
            throw new BizException(503, "服务端未安装文档转换组件（LibreOffice），暂无法处理该格式，请上传 PDF");
        }
        Path outDir = source.getParent();
        // 每个转换任务独立 UserInstallation，避免多请求并发时 LibreOffice 配置目录互锁
        String profile = "file:///" + Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("lo-profile-" + UUID.randomUUID()).toAbsolutePath().toString().replace('\\', '/');

        List<String> cmd = new ArrayList<>();
        cmd.add(soffice);
        cmd.add("--headless");
        cmd.add("--norestore");
        cmd.add("--nolockcheck");
        cmd.add("-env:UserInstallation=" + profile);
        cmd.add("--convert-to");
        cmd.add("pdf:writer_pdf_Export");
        cmd.add("--outdir");
        cmd.add(outDir.toAbsolutePath().toString());
        cmd.add(source.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process p = pb.start();
            boolean done = p.waitFor(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
            String output = new String(p.getInputStream().readAllBytes());
            if (!done) {
                p.destroyForcibly();
                throw new BizException(504, "文档转换超时，请尝试上传 PDF 后打印");
            }
            String baseName = source.getFileName().toString();
            int dot = baseName.lastIndexOf('.');
            Path pdf = outDir.resolve((dot > 0 ? baseName.substring(0, dot) : baseName) + ".pdf");
            if (!Files.exists(pdf)) {
                log.warn("文档转换未产出 PDF，ext={} output={}", ext, output);
                throw new BizException(400, "文档转换失败，请确认文件未损坏，或改用 PDF 上传");
            }
            log.info("文档转换成功 {} -> {} ({} bytes)", source.getFileName(), pdf.getFileName(), Files.size(pdf));
            return pdf;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("文档转换异常 ext={}", ext, e);
            throw new BizException(500, "文档转换异常，请稍后重试或上传 PDF");
        }
    }

    private String findSoffice(String configured) {
        if (configured != null && !configured.isBlank()) {
            File f = new File(configured);
            return f.canExecute() ? f.getAbsolutePath() : null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add("soffice");
        candidates.add("libreoffice");
        candidates.add("/usr/bin/soffice");
        candidates.add("/usr/lib/libreoffice/program/soffice");
        candidates.add("/Applications/LibreOffice.app/Contents/MacOS/soffice");
        candidates.add("C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        candidates.add("C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe");
        for (String c : candidates) {
            if (c.contains("/") || c.contains("\\")) {
                if (new File(c).canExecute()) {
                    return c;
                }
                continue;
            }
            String onPath = which(c);
            if (onPath != null) {
                return onPath;
            }
        }
        return null;
    }

    private String which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : new String[]{exe, exe + ".exe", exe + ".bat"}) {
                File f = new File(dir, name);
                if (f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }
}
