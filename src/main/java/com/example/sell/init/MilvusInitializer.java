package com.example.sell.init;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author 屈轩
 */
@Component
public class MilvusInitializer {

    private final MilvusClientV2 client;

    // 构造器注入，Spring 保证 client 已就绪
    public MilvusInitializer(MilvusClientV2 client) {
        this.client = client;
    }

    @PostConstruct
    public void init() {
        createDatabase();
        createCollection();
        createChatMessageCollection();
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

    // ========== 第二步：建集合 ==========
    private void createCollection() {
        String collectionName = "product";

        // 先判断集合是否已存在
        if (client.hasCollection(
                HasCollectionReq.builder()
                    .databaseName("sell")
                    .collectionName(collectionName)
                    .build())
        ) {
            System.out.println("集合 " + collectionName + " 已存在，跳过");
            return;
        }

        // 1. 创建 Schema
        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        // 2. 添加字段
        // 主键字段（必须有，autoID = true 则无需手动传值）
        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .build());

        // 普通标量字段
        schema.addField(AddFieldReq.builder()
                .fieldName("product_name")
                .dataType(DataType.VarChar)
                .maxLength(256)
                .build());

        // 向量字段（存商品描述的语义向量，维度按你的模型来）
        schema.addField(AddFieldReq.builder()
                .fieldName("description_vector")
                .dataType(DataType.FloatVector)
                .dimension(1536)   // 比如 OpenAI text-embedding-3-small 是 1536 维
                .build());

        // 3. 配置索引（向量字段必须建索引）
        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("description_vector")
                .indexType(IndexParam.IndexType.IVF_FLAT)  // 常用索引类型
                .metricType(IndexParam.MetricType.COSINE)  // 余弦相似度
                .extraParams(Map.of("nlist", 128))
                .build());

        // 4. 发起建集合请求（指定数据库 sell）
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

    // ========== 第三步：建对话消息集合（用于语义搜索） ==========
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
}
//```
//
//---
//
//**核心概念说明：**
//
//| 概念 | 对应关系型数据库 | 说明 |
//|------|----------------|------|
//| Database | Database | 逻辑隔离的库 |
//| Collection | Table（表） | 数据存储单元 |
//| Field | Column（列） | 字段 |
//| FloatVector | 无对应 | 存向量数据，**必须有** |
//| Index | Index（索引） | 向量字段**必须建索引**才能搜索 |
//
//**常用索引类型选择：**
//```
//数据量 < 100万  →  FLAT（精确但慢）
//数据量适中     →  IVF_FLAT（推荐，速度/精度均衡）
//追求极速       →  HNSW
//```
//
//**常用相似度类型：**
//```
//文本语义搜索   →  COSINE（余弦相似度）
//图像/特征向量  →  L2（欧氏距离）