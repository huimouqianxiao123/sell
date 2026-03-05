package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.domain.Dto.LoginRequest;
import com.example.sell.domain.Dto.RegisterRequest;
import com.example.sell.domain.vo.LoginVo;
import com.example.sell.domain.vo.UserVO;
import com.example.sell.service.Imp.UserServiceImp;
import com.example.sell.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;



/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    @Resource
    private UserService userService;


    @PostMapping("/login")
    public R<LoginVo> login(@RequestBody LoginRequest loginRequest) {
     return R.ok(userService.login(loginRequest));
    }

    @PostMapping("/register")
    public R<UserVO> register(@RequestBody RegisterRequest registerRequest) {
        UserVO userVO = userService.register(registerRequest);
        return R.ok(userVO);
    }


}
