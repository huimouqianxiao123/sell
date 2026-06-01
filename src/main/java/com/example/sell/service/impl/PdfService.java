package com.example.sell.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * @author 屈轩
 */
@Service
public class PdfService {
    private static final String API_KEY = "xPdpJuulWnPl1nlgaOEAYeAk";
    private static final String SECRET_KEY = "qnYWqxU1pSRwhQrcmlQYNHUokxM3u659";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource(name = "cpuTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /**
     * 将 PDF 每页渲染为图片并并发 OCR，返回拼接后的 Markdown 字符串
     */
    public String ocrPdf(MultipartFile multipartFile) throws IOException {
        if (multipartFile.isEmpty()) {
            throw new IOException("上传的文件为空");
        }
        return ocrPdfBytes(multipartFile.getInputStream().readAllBytes());
    }

    /**
     * 从字节数组处理 PDF，返回 Markdown
     */
    public String ocrPdfBytes(byte[] pdfBytes) throws IOException {
        int totalPages;
        try (PDDocument tempDoc = Loader.loadPDF(pdfBytes)) {
            totalPages = tempDoc.getNumberOfPages();
        }

        ExecutorService executor = threadPoolTaskExecutor.getThreadPoolExecutor();
        ConcurrentHashMap<Integer, String> pageResults = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int page = 0; page < totalPages; page++) {
            final int pageNum = page;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try (PDDocument localDoc = Loader.loadPDF(pdfBytes)) {
                    String directText = extractTextFromPage(localDoc, pageNum);
                    if (!directText.isBlank()) {
                        pageResults.put(pageNum, directText);
                        return;
                    }

                    PDFRenderer renderer = new PDFRenderer(localDoc);
                    BufferedImage image = renderer.renderImageWithDPI(pageNum, 150);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", baos);
                    byte[] imgBytes = baos.toByteArray();

                    String ocrText = ocrImageFromBytes(imgBytes);
                    pageResults.put(pageNum, ocrText);
                } catch (Exception e) {
                    pageResults.put(pageNum, "");
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < totalPages; i++) {
            String text = pageResults.getOrDefault(i, "");
            if (!text.isBlank()) {
                markdown.append("## 第 ").append(i + 1).append(" 页\n\n");
                markdown.append(text).append("\n\n");
            }
        }
        return markdown.toString();
    }

    private String extractTextFromPage(PDDocument document, int pageNum) throws IOException {
        PDFTextStripper textStripper = new PDFTextStripper();
        textStripper.setStartPage(pageNum + 1);
        textStripper.setEndPage(pageNum + 1);
        textStripper.setSortByPosition(true);
        return textStripper.getText(document).trim();
    }

    /**
     * 对图片字节数组调用百度 OCR，返回识别文字
     */
    public String ocrImageFromBytes(byte[] imageBytes) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        String authUrl = TOKEN_URL + "?grant_type=client_credentials&client_id=" + API_KEY + "&client_secret=" + SECRET_KEY;
        HttpRequest tokenReq = HttpRequest.newBuilder().uri(URI.create(authUrl)).GET().build();
        HttpResponse<String> tokenRes = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        String accessTokenStr = OBJECT_MAPPER.readTree(tokenRes.body()).get("access_token").asText();

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String body = "image=" + URLEncoder.encode(base64Image, StandardCharsets.UTF_8);
        String ocrFullUrl = OCR_URL + "?access_token=" + accessTokenStr;
        HttpRequest ocrReq = HttpRequest.newBuilder()
                .uri(URI.create(ocrFullUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> ocrRes = client.send(ocrReq, HttpResponse.BodyHandlers.ofString());

        JsonNode root = OBJECT_MAPPER.readTree(ocrRes.body());
        JsonNode words = root.get("words_result");
        if (words == null || !words.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode word : words) {
            sb.append(word.get("words").asText()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 对 MultipartFile 图片调用 OCR（保留原接口）
     */
    public String ocrImage(MultipartFile multipartFile) throws IOException, InterruptedException {
        return ocrImageFromBytes(multipartFile.getBytes());
    }
}
