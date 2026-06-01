package com.example.sell.service.impl;

import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sell.dao.UserMapper;
import com.example.sell.dto.LoginRequest;
import com.example.sell.dto.RegisterRequest;
import com.example.sell.entity.User;
import com.example.sell.vo.LoginVo;
import com.example.sell.vo.UserVO;

import com.example.sell.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

/**
 * @author 屈轩
 */
@Service
public class UserServiceImp extends ServiceImpl<UserMapper, User> implements UserService {


    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String LOGIN_TOKEN_KEY = "login:token:";
    @Value("${login.token.expire:86400}")
    private long tokenExpire;
    @Override
    public UserVO register(RegisterRequest registerRequest) {
        // 检查用户名是否存在
        boolean exists = lambdaQuery().eq(User::getUsername, registerRequest.getUsername()).exists();
        if (exists) {
            throw new RuntimeException("用户名已存在");
        }

        // 创建用户
        User user = User.builder()
                .username(registerRequest.getUsername())
                .password(registerRequest.getPassword())
                .role(registerRequest.getRole())
                .build();
        // 保存用户
        save(user);
        // 返回UserVO
        UserVO userVO = new UserVO();
        userVO.setId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setRole(user.getRole());
        return userVO;
    }

    @Override
    public LoginVo login(LoginRequest loginRequest) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();
        if (!this.lambdaQuery().eq(User::getUsername, username).exists()) {
            throw new RuntimeException("用户不存在");
        }
        if (!this.lambdaQuery().eq(User::getUsername, username).eq(User::getPassword, password).exists()) {
            throw new RuntimeException("用户名或密码错误");
        }
        User user = this.lambdaQuery().eq(User::getUsername, username).eq(User::getPassword, password).one();
        String token=UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_TOKEN_KEY + token;
        UserVO userVO = UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
        
        try {
            redisTemplate.opsForValue().set(tokenKey, userVO, tokenExpire, TimeUnit.SECONDS);
        } catch (DataAccessException e) {
            throw new RuntimeException("登录失败，Redis连接异常", e);
        }
        
        LoginVo loginVo = new LoginVo();
        loginVo.setToken(token);
        loginVo.setUserVO(userVO);
        return loginVo;
    }
}