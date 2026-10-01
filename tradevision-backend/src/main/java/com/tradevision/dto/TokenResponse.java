package com.tradevision.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public class TokenResponse {
    private String  accessToken;
    private String  refreshToken;
    private UserDto user;
}
