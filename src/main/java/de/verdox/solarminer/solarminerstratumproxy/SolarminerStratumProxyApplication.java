package de.verdox.solarminer.solarminerstratumproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SolarminerStratumProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(SolarminerStratumProxyApplication.class, args);
    }
}
