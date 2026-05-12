package tech.wenisch.proxera;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProxeraApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxeraApplication.class, args);
    }
}
