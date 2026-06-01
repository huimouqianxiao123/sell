package com.example.sell.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sell.dto.LoginRequest;
import com.example.sell.dto.RegisterRequest;
import com.example.sell.entity.User;
import com.example.sell.vo.LoginVo;
import com.example.sell.vo.UserVO;

/**
 * @author 屈轩
 */
public interface UserService extends IService<User> {
    UserVO register(RegisterRequest registerRequest);
    LoginVo login(LoginRequest loginRequest);
}
