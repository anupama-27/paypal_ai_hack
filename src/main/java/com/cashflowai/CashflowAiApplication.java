package com.cashflowai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CashflowAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashflowAiApplication.class, args);
    }
}
