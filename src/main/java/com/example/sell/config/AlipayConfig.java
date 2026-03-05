package com.example.sell.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data // 自动生成 Getter、Setter、ToString 等
@Component // 注册为 Spring Bean，这样你才能在 Controller 里 @Autowired
@ConfigurationProperties(prefix = "alipay") // 读取 application.yml 中 alipay 开头的配置
public class AlipayConfig {
    
    private String appId;
    
    private String merchantPrivateKey;
    
    private String alipayPublicKey;
    
    private String notifyUrl;
    
    private String returnUrl;
    
    private String signType;
    
    private String charset;
    
    private String gatewayUrl;
}