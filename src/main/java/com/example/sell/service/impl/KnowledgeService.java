package com.example.sell.service.impl;

import cn.hutool.json.JSONUtil;
import com.example.sell.common.R;
import com.example.sell.dao.KnowledgeDocMapper;
import com.example.sell.entity.KnowledgeDoc;
import com.example.sell.utils.MinIOUtils;
import io.minio.errors.MinioException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author 屈轩
 */
@Slf4j
@Service
public class KnowledgeService {
    private final MinIOUtils minIOUtils;
    private final RocketMQTemplate rocketMQTemplate;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeService(MinIOUtils minIOUtils, RocketMQTemplate rocketMQTemplate,
                             KnowledgeDocMapper knowledgeDocMapper, JdbcTemplate jdbcTemplate) {
        this.minIOUtils = minIOUtils;
        this.rocketMQTemplate = rocketMQTemplate;
        this.knowledgeDocMapper = knowledgeDocMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_doc (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    file_name VARCHAR(512) NOT NULL COMMENT '原始文件名',
                    file_type VARCHAR(128) NOT NULL COMMENT 'MIME 类型',
                    minio_url VARCHAR(1024) NOT NULL COMMENT 'MinIO 存储 URL',
                    markdown_content LONGTEXT NULL COMMENT 'OCR 后的 markdown 原文',
                    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=待处理,1=处理中,2=已完成,3=失败',
                    error_msg VARCHAR(1024) NULL COMMENT '失败原因',
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表'
                """);
    }

    public R<String> addKnowledge(MultipartFile file) {
        if (file.isEmpty()) {
            return R.error("上传失败：文件为空");
        }
        String fileType = resolveFileType(file);
        if (!"application/pdf".equals(fileType)) {
            return R.error("暂只支持 PDF 文件");
        }
        try {
            String url = minIOUtils.uploadFile(file);
            if (url == null) {
                return R.error("上传 MinIO 失败");
            }

            // 截取 MinIO 返回 URL 中的文件名部分（最后一段）
            String fileName = url.substring(url.lastIndexOf('/') + 1);

            // 先入库，status=0（待处理）
            KnowledgeDoc doc = KnowledgeDoc.builder()
                    .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : fileName)
                    .fileType(fileType)
                    .minioUrl(url)
                    .status(0)
                    .build();
            knowledgeDocMapper.insert(doc);

            // 发送消息到 knowledge-topic
            Map<String, Object> msg = new HashMap<>();
            msg.put("docId", doc.getId());
            msg.put("url", url);
            msg.put("fileName", doc.getFileName());
            msg.put("fileType", fileType);
            rocketMQTemplate.convertAndSend("knowledge-topic", JSONUtil.toJsonStr(msg));

            log.info("[知识库] 文件上传成功，docId={}, url={}", doc.getId(), url);
            return R.ok("上传成功，正在后台处理");
        } catch (IOException | MinioException | NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("[知识库] 上传异常", e);
            return R.error("上传失败：" + e.getMessage());
        }
    }

    private String resolveFileType(MultipartFile file) {
        String contentType = file.getContentType();
        if ("application/pdf".equalsIgnoreCase(contentType)) {
            return "application/pdf";
        }

        if (hasPdfHeader(file)) {
            return "application/pdf";
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null
                && originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf")
                && "application/octet-stream".equalsIgnoreCase(contentType)) {
            return "application/pdf";
        }
        return contentType;
    }

    private boolean hasPdfHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(5);
            return header.length == 5
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F'
                    && header[4] == '-';
        } catch (IOException e) {
            log.warn("[知识库] 读取文件头失败，fileName={}", file.getOriginalFilename(), e);
            return false;
        }
    }
}
