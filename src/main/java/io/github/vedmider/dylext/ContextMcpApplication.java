package io.github.vedmider.dylext;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ContextMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContextMcpApplication.class, args);
    }
}
