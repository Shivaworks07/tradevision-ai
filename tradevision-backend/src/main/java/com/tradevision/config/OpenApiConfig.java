package com.tradevision.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.*;
import io.swagger.v3.oas.annotations.security.*;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title       = "TradeVision AI API",
        version     = "v1",
        description = "AI-powered trading analysis platform — Indian Stocks, Crypto, Forex",
        contact     = @Contact(name = "Basu", email = "basu@tradevision.ai")
    ),
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name   = "bearerAuth",
    type   = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
public class OpenApiConfig {}
