package com.tradevision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "First name is required")
    @Size(min=2, max=50) private String firstName;
    private String lastName;
    @NotBlank(message = "Mobile number is required")
    @Pattern(regexp="^[6-9]\\d{9}$", message="Enter valid 10-digit Indian mobile number")
    private String mobile;
    private String tradingPlatform;
}
