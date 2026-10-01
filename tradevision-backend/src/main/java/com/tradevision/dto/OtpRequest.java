package com.tradevision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class OtpRequest {
    @NotBlank
    @Pattern(regexp="^[6-9]\\d{9}$", message="Valid 10-digit mobile required")
    private String mobile;
    @NotBlank private String otp;
    private String purpose; // REGISTER or LOGIN
}
