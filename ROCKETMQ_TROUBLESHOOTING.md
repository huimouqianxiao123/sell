# RocketMQ 故障排查指南

## 问题描述
秒杀系统中出现MQ消息发送失败的错误：
```
【秒杀】MQ消息发送失败，用户ID: 3998, 商品ID: 2018188321206472705
订单创建失败，请重试
```

## 已实施的修复

### 1. 增强错误日志记录
- 修改了 `RocketMQMessageService.syncSend` 方法，现在会记录详细的异常类型和异常信息
- 这样可以更准确地定位问题原因

### 2. 添加重试机制
- 在 `SeckillProductServiceImp.sendToMq` 方法中添加了3次重试机制
- 每次重试间隔100毫秒
- 提高了消息发送的成功率

### 3. 添加RocketMQ健康检查
- 创建了 `RocketMQConfig` 配置类，在启动时检查RocketMQ连接状态
- 创建了 `RocketMQUtils` 工具类，提供连接测试功能
- 创建了 `RocketMQHealthController` 控制器，提供健康检查接口

## 使用健康检查接口

### 测试RocketMQ连接
```bash
GET http://localhost:8080/api/rocketmq/health
```

响应示例：
```json
{
  "code": 200,
  "message": "success",
  "data": "RocketMQ连接正常，NameServer: 43.139.17.130:9876, 生产者组: my-producer-group"
}
```

### 获取RocketMQ配置信息
```bash
GET http://localhost:8080/api/rocketmq/config
```

## 常见问题排查

### 1. NameServer连接失败
**症状**：日志显示无法连接到NameServer

**解决方案**：
- 检查 `application.yml` 中的 `rocketmq.name-server` 配置是否正确
- 确认NameServer地址 `43.139.17.130:9876` 是否可访问
- 检查防火墙是否开放9876端口
- 尝试使用 `telnet 43.139.17.130 9876` 测试连接

### 2. Topic不存在
**症状**：日志显示Topic不存在

**解决方案**：
- 在RocketMQ控制台创建 `seckill-topic` 主题
- 或者修改代码使用已存在的Topic

### 3. ACL认证失败
**症状**：日志显示认证失败

**解决方案**：
- 检查 `application.yml` 中的 `rocketmq.producer.access-key` 和 `secret-key` 配置
- 确认RocketMQ服务端是否开启了ACL认证
- 如果未开启ACL，可以删除access-key和secret-key配置

### 4. 网络超时
**症状**：日志显示连接超时

**解决方案**：
- 增加 `rocketmq.producer.send-message-timeout` 配置值（当前为3000ms）
- 检查网络延迟是否过高
- 考虑将RocketMQ部署在更近的网络环境

## 配置优化建议

### application.yml 中的RocketMQ配置
```yaml
rocketmq:
  name-server: 43.139.17.130:9876
  producer:
    group: my-producer-group
    send-message-timeout: 5000  # 增加超时时间
    retry-times-when-send-failed: 3  # 增加重试次数
    access-key: admin
    secret-key: admin123
```

## 启动检查清单

1. 确认RocketMQ NameServer已启动
2. 确认RocketMQ Broker已启动
3. 确认 `seckill-topic` 主题已创建
4. 确认网络连接正常
5. 确认ACL认证配置正确（如果启用）
6. 调用健康检查接口确认连接正常

## 下一步操作

1. 重启应用，观察启动日志中的RocketMQ初始化信息
2. 调用健康检查接口确认连接状态
3. 如果连接失败，根据错误信息进行排查
4. 如果连接成功，进行秒杀测试
5. 观察日志中的MQ消息发送情况