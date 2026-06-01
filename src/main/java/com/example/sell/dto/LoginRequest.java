package com.example.sell.dto;

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
