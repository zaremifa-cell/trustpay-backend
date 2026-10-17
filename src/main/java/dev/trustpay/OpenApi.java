package dev.trustpay;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.*;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "TrustPay API",
            version = "1.0",
            description =
                "Synthetic payment orchestration, identity decisions and balanced ledger"),
    security = {
      @SecurityRequirement(name = "bearerAuth"),
      @SecurityRequirement(name = "basicAuth")
    })
@SecuritySchemes({
  @SecurityScheme(
      name = "bearerAuth",
      type = SecuritySchemeType.HTTP,
      scheme = "bearer",
      bearerFormat = "JWT"),
  @SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
})
class OpenApi {}
