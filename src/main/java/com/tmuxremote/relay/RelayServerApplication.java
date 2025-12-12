package com.tmuxremote.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RelayServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayServerApplication.class, args);
    }
}
