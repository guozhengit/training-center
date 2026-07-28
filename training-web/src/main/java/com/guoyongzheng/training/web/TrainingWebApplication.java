package com.guoyongzheng.training.web;

import com.guoyongzheng.training.web.config.TrainingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.guoyongzheng.training")
@EnableConfigurationProperties(TrainingProperties.class)
public class TrainingWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrainingWebApplication.class, args);
    }
}
