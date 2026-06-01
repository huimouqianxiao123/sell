package com.example.sell;

import com.example.sell.common.R;
import com.example.sell.dao.KnowledgeDocMapper;
import com.example.sell.entity.KnowledgeDoc;
import com.example.sell.service.impl.KnowledgeService;
import com.example.sell.utils.MinIOUtils;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    @Test
    void shouldAcceptPdfWhenBrowserReportsOctetStream() throws Exception {
        MinIOUtils minIOUtils = mock(MinIOUtils.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        KnowledgeDocMapper knowledgeDocMapper = mock(KnowledgeDocMapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeService knowledgeService = new KnowledgeService(
                minIOUtils, rocketMQTemplate, knowledgeDocMapper, jdbcTemplate);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo.pdf",
                "application/octet-stream",
                "%PDF-1.7\nhello".getBytes()
        );

        when(minIOUtils.uploadFile(any())).thenReturn("http://minio/photo/demo.pdf");
        doAnswer(invocation -> {
            KnowledgeDoc doc = invocation.getArgument(0);
            doc.setId(1L);
            return 1;
        }).when(knowledgeDocMapper).insert(any(KnowledgeDoc.class));

        R<String> result = knowledgeService.addKnowledge(file);

        ArgumentCaptor<KnowledgeDoc> docCaptor = ArgumentCaptor.forClass(KnowledgeDoc.class);
        assertTrue(result.success());
        assertEquals("上传成功，正在后台处理", result.getData());
        org.mockito.Mockito.verify(knowledgeDocMapper).insert(docCaptor.capture());
        assertEquals("application/pdf", docCaptor.getValue().getFileType());
        org.mockito.Mockito.verify(rocketMQTemplate).convertAndSend(eq("knowledge-topic"), any(String.class));
    }
}
