package com.tradevision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    @Pattern(regexp="^[6-9]\\d{9}$") private String mobile;
}
