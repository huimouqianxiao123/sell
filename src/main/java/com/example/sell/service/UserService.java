package com.example.sell.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sell.domain.Dto.LoginRequest;
import com.example.sell.domain.Dto.RegisterRequest;
import com.example.sell.domain.pojo.User;
import com.example.sell.domain.vo.LoginVo;
import com.example.sell.domain.vo.UserVO;

/**
 * @author 屈轩
 */
public interface UserService extends IService<User> {
    UserVO register(RegisterRequest registerRequest);
    LoginVo login(LoginRequest loginRequest);
}
