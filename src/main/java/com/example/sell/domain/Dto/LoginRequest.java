package com.example.sell.domain.Dto;

import lombok.Data;

/**
 * @author 屈轩
 */
@Data
public class LoginRequest {
    private String username;
    private String role;
    private String password;
}
