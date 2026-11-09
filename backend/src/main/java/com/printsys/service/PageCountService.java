package com.printsys.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 页数统计：
 * - 首选对「转换后的 PDF」用 PDFBox 统计，结果即实际打印页数；
 * - 仅在转换不可用且允许降级时，才对 DOCX/XLSX/TXT 做估算（结果会标记 page_source=ESTIMATE）。
 */
@Slf4j
@Service
public class PageCountService {

    public int countPdfPages(File pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return Math.max(1, doc.getNumberOfPages());
        } catch (Exception e) {
            log.warn("PDF 页数解析失败 {}: {}", pdf.getName(), e.getMessage());
            throw new IllegalArgumentException("PDF 文件无法解析，请确认文件未损坏");
        }
    }

    /** 降级估算（仅在服务端转换关闭且允许降级时使用） */
    public int estimate(File file, String ext) {
        try {
            return switch (ext.toLowerCase()) {
                case "pdf" -> countPdfPages(file);
                case "png", "jpg", "jpeg", "gif", "bmp", "webp" -> 1;
                case "docx" -> countDocx(file);
                case "xlsx" -> countXlsx(file);
                case "txt" -> countTxt(file);
                default -> 1;
            };
        } catch (Exception e) {
            log.warn("页数估算失败，按 1 页处理 file={} err={}", file.getName(), e.getMessage());
            return 1;
        }
    }

    private int countDocx(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            int pages = doc.getProperties().getExtendedProperties().getPages();
            if (pages > 0) {
                return pages;
            }
            return Math.max(1, (int) Math.ceil(doc.getParagraphs().size() / 40.0));
        }
    }

    private int countXlsx(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            return Math.max(1, wb.getNumberOfSheets());
        }
    }

    private int countTxt(File file) throws Exception {
        long lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).size();
        return Math.max(1, (int) Math.ceil(lines / 50.0));
    }
}
