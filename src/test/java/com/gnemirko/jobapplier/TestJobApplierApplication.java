package com.gnemirko.jobapplier;

import org.springframework.boot.SpringApplication;

public class TestJobApplierApplication {

    public static void main(String[] args) {
        SpringApplication.from(JobApplierApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
