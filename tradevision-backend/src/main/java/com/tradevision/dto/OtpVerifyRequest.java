package com.tradevision.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class OtpVerifyRequest {
    private String email;
    private String mobile;  // legacy

    @JsonAlias({"otp", "code"})   // frontend sends "otp", accept both
    private String code;

    private String purpose;       // set by controller: REGISTER or LOGIN
    private String firstName;
    private String lastName;
}
