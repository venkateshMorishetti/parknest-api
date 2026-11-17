package com.parknest.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ParknestApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParknestApiApplication.class, args);
    }
}
