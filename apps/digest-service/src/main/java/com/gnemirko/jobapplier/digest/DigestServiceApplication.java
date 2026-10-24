package com.gnemirko.jobapplier.digest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DigestServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigestServiceApplication.class, args);
    }
}
