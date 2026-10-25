package com.gnemirko.jobapplier.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** No component scan of settings here: UiConfig registers them, and only when the UI is enabled. */
@SpringBootApplication
public class WebUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebUiApplication.class, args);
    }
}
