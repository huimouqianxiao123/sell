package com.example.sell.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * @author 屈轩
 * 文件处理类
 */
@Service
public class FileTurnService {
    private final PdfService pdfService;

    public FileTurnService(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    public String turnFile(MultipartFile multipartFile) throws IOException, InterruptedException {
        String contentType = multipartFile.getContentType();
        if (contentType != null && contentType.equals("application/pdf")) {
            return pdfService.ocrPdf(multipartFile);
        }
        throw new IllegalArgumentException("暂不支持的文件类型: " + contentType);
    }
}