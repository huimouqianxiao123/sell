package com.example.sell.init;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author 屈轩
 */
@Component
public class MilvusInitializer {

    private final MilvusClientV2 client;

    @Value("${ai.milvus.init-chat-message-collection:true}")
    private boolean initChatMessageCollection;

    @Value("${ai.milvus.init-kb-collection:true}")
    private boolean initKbCollection;

    // 构造器注入，Spring 保证 client 已就绪
    public MilvusInitializer(@Lazy MilvusClientV2 client) {
        this.client = client;
    }

    @PostConstruct
    public void init() {
        createDatabase();
        if (initChatMessageCollection) {
            createChatMessageCollection();
        } else {
            System.out.println("集合 chat_message 创建已关闭（ai.milvus.init-chat-message-collection=false）");
        }
        if (initKbCollection) {
            createKnowledgeChunkCollection();
        } else {
            System.out.println("集合 kb_chunk 创建已关闭（ai.milvus.init-kb-collection=false）");
        }
    }

    // ========== 第一步：建库 ==========
    private void createDatabase() {
        List<String> databases = client.listDatabases().getDatabaseNames();
        if (!databases.contains("sell")) {
            client.createDatabase(
                CreateDatabaseReq.builder()
                    .databaseName("sell")
                    .build()
            );
            System.out.println("数据库 sell 创建成功");
        } else {
            System.out.println("数据库 sell 已存在，跳过");
        }
    }

    // ========== 第二步：建对话消息集合（用于语义搜索） ==========
    private void createChatMessageCollection() {
        String collectionName = "chat_message";

        if (client.hasCollection(
                HasCollectionReq.builder()
                    .databaseName("sell")
                    .collectionName(collectionName)
                    .build())
        ) {
            System.out.println("集合 " + collectionName + " 已存在，跳过");
            return;
        }

        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        // 主键
        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .build());

        // 会话 ID
        schema.addField(AddFieldReq.builder()
                .fieldName("session_id")
                .dataType(DataType.VarChar)
                .maxLength(128)
                .build());

        // 消息角色（user / assistant）
        schema.addField(AddFieldReq.builder()
                .fieldName("role")
                .dataType(DataType.VarChar)
                .maxLength(32)
                .build());

        // 消息原文
        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(DataType.VarChar)
                .maxLength(8192)
                .build());

        // 消息向量（text-embedding-v1 维度 1536）
        schema.addField(AddFieldReq.builder()
                .fieldName("content_vector")
                .dataType(DataType.FloatVector)
                .dimension(1536)
                .build());

        // 创建时间（毫秒时间戳，便于按时间范围过滤）
        schema.addField(AddFieldReq.builder()
                .fieldName("create_time")
                .dataType(DataType.Int64)
                .build());

        // 向量索引
        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("content_vector")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", 128))
                .build());

        client.createCollection(
            CreateCollectionReq.builder()
                .databaseName("sell")
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexes)
                .build()
        );

        System.out.println("集合 " + collectionName + " 创建成功（数据库: sell）");
    }

    // ========== 第三步：建知识库分片集合（用于主 RAG） ==========
    private void createKnowledgeChunkCollection() {
        String collectionName = "kb_chunk";

        if (client.hasCollection(
                HasCollectionReq.builder()
                        .databaseName("sell")
                        .collectionName(collectionName)
                        .build())
        ) {
            System.out.println("集合 " + collectionName + " 已存在，跳过");
            return;
        }

        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("category")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("source")
                .dataType(DataType.VarChar)
                .maxLength(256)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("title")
                .dataType(DataType.VarChar)
                .maxLength(256)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(DataType.VarChar)
                .maxLength(4096)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("publish_time")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("content_vector")
                .dataType(DataType.FloatVector)
                .dimension(1536)
                .build());

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("content_vector")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", 128))
                .build());

        client.createCollection(
                CreateCollectionReq.builder()
                        .databaseName("sell")
                        .collectionName(collectionName)
                        .collectionSchema(schema)
                        .indexParams(indexes)
                        .build()
        );

        System.out.println("集合 " + collectionName + " 创建成功（数据库: sell）");
    }
}
