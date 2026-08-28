package com.edevlet.lineage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PedigreeLineageApplication {

    public static void main(String[] args) {
        SpringApplication.run(PedigreeLineageApplication.class, args);
    }
}
