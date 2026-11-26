package dev.justcallmepratt.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ledger-core API")
                .version("v1")
                .description("Double-entry ledger: TigerBeetle-style accounts and transfers on PostgreSQL. "
                        + "Amounts are decimal integer strings in minor units. Errors are RFC 9457 problem+json "
                        + "with a stable `code`."));
    }
}
