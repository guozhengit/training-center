package com.guoyongzheng.training.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI trainingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Training Center API")
                        .description("Interview training session management, judging, and dashboard API.")
                        .version("1.0.0")
                        .contact(new Contact().name("guoyongzheng")));
    }
}
