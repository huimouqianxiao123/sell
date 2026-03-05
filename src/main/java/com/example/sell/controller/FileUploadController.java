package com.example.sell.controller;

import com.example.sell.utils.MinIOUtils;
import io.minio.errors.MinioException;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/file")
public class FileUploadController {
    @Resource
    private MinIOUtils minioUtils;

    /**
     * 上传文件
     * @param multipartFile
     * @return
     */
    @RequestMapping("/file")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile multipartFile) {
        if (multipartFile.isEmpty()) {
            return ResponseEntity.badRequest().body("上传的文件不能为空");
        }

        try {
            String url = minioUtils.uploadFile(multipartFile);
            return ResponseEntity.ok(url);
        } catch (IOException | MinioException | NoSuchAlgorithmException | InvalidKeyException e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("文件上传失败: " + e.getMessage());
        }
    }

    @RequestMapping("/files")
    public ResponseEntity<List<String>> uploadFiles(@RequestParam("files")MultipartFile[] multipartFiles) {
        // 检查是否有空文件
        for (MultipartFile multipartFile : multipartFiles) {
            if (multipartFile.isEmpty()) {
                return ResponseEntity.badRequest().body(List.of("上传的文件不能为空"));
            }
        }

        try {
            List<String> urls = minioUtils.uploadFiles(multipartFiles);
            return ResponseEntity.ok(urls);
        } catch (MinioException e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(List.of("文件上传失败: " + e.getMessage()));
        }
    }
}
