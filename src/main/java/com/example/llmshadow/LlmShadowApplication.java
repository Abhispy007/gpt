package com.example.llmshadow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LlmShadowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmShadowApplication.class, args);
    }
}
