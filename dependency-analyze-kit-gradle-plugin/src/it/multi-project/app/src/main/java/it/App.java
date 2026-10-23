package it;

import it.lib.Greeter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class App {
    @Bean
    Greeter greeter() {
        return new Greeter();
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
