package com.example.sell;

import com.example.sell.service.impl.PdfService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfServiceTest {

    @Test
    void shouldExtractTextPdfWithoutCallingOcr() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();

        PdfService pdfService = new PdfService() {
            @Override
            public String ocrImageFromBytes(byte[] imageBytes) {
                throw new AssertionError("文本型 PDF 不应走 OCR");
            }
        };
        ReflectionTestUtils.setField(pdfService, "threadPoolTaskExecutor", executor);

        String text = "Hello PDF";
        byte[] pdfBytes = createTextPdf(text);

        String result = pdfService.ocrPdfBytes(pdfBytes);

        assertTrue(result.contains(text));
    }

    private byte[] createTextPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(100, 700);
                contentStream.showText(text);
                contentStream.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}
