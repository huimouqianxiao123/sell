package com.example.sell.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 屈轩
 * 登录响应VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVo {
    /**
     * JWT令牌
     */
    private String token;
    
    /**
     * 用户信息
     */
    private UserVO userVO;
}
