package com.example.sell.utils;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.errors.MinioException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * MinIO 工具类：支持单文件/多文件上传并返回永久公开 URL
 * 要求 MinIO 的 'photo' 存储桶已设置为 public-read（公开读）
 *
 * @author 屈轩
 */
@Component
public class MinIOUtils {

    // 根据docker部署配置，使用19000端口作为API端口
    private static final String ENDPOINT = "http://43.139.17.130:9000";
    private static final String ACCESS_KEY = "minio";
    private static final String SECRET_KEY = "8CNTFmeZk7PibDns";
    private static final String BUCKET_NAME = "photo";

    @Resource(name = "ioTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    private MinioClient minioClient;

    @PostConstruct
    public void init() {
        this.minioClient = MinioClient.builder()
                .endpoint(ENDPOINT)
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
                
        // 设置存储桶策略为公共读
        setBucketPolicy();
    }

    /**
     * 设置存储桶策略为公共读
     */
    private void setBucketPolicy() {
        try {
            // 定义公共读策略
            String policy = "{\n" +
                    "  \"Version\": \"2012-10-17\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": {\n" +
                    "        \"AWS\": [\"*\"]\n" +
                    "      },\n" +
                    "      \"Action\": [\"s3:GetBucketLocation\", \"s3:ListBucket\"],\n" +
                    "      \"Resource\": [\"arn:aws:s3:::" + BUCKET_NAME + "\"]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": {\n" +
                    "        \"AWS\": [\"*\"]\n" +
                    "      },\n" +
                    "      \"Action\": [\"s3:GetObject\"],\n" +
                    "      \"Resource\": [\"arn:aws:s3:::" + BUCKET_NAME + "/*\"]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";
            
            minioClient.setBucketPolicy(
                SetBucketPolicyArgs.builder()
                    .bucket(BUCKET_NAME)
                    .config(policy)
                    .build()
            );
        } catch (Exception e) {
            System.err.println("设置存储桶策略失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 上传单个文件并返回永久公开 URL（要求 bucket 为 public-read）
     */
    public String uploadFile(MultipartFile file) throws IOException, MinioException, NoSuchAlgorithmException, InvalidKeyException {
        try {
            String fileName = file.getOriginalFilename();
            if (fileName == null || fileName.isEmpty()) {
                throw new IllegalArgumentException("文件名不能为空");
            }

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(fileName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );

            // 返回永久公开 URL（无需签名）
            return ENDPOINT + "/" + BUCKET_NAME + "/" + fileName;
        } catch (Exception e) {
            // 记录详细错误信息
            e.printStackTrace();
            throw new MinioException("上传失败: " + e.getMessage());
        }
    }

    /**
     * 批量异步上传多个文件，返回所有永久公开 URL 列表
     */
    public List<String> uploadFiles(MultipartFile[] files) throws MinioException {
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (MultipartFile file : files) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String fileName = file.getOriginalFilename();
                    if (fileName == null || fileName.isEmpty()) {
                        throw new IllegalArgumentException("文件名不能为空");
                    }

                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(BUCKET_NAME)
                                    .object(fileName)
                                    .stream(file.getInputStream(), file.getSize(), -1)
                                    .contentType(file.getContentType())
                                    .build()
                    );

                    return ENDPOINT + "/" + BUCKET_NAME + "/" + fileName;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException("异步上传失败: " + e.getMessage(), e);
                }
            }, threadPoolTaskExecutor);

            futures.add(future);
        }

        // 等待所有任务完成并收集结果
        List<String> urls = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            try {
                urls.add(future.join());
            } catch (Exception e) {
                // 可根据业务需求选择跳过失败项或抛出异常
                throw new MinioException("批量上传过程中出错: " + e.getCause().getMessage());
            }
        }

        return urls;
    }
}