package it;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class App {
    @Bean
    Object databaseConsumer(DataSource dataSource) {
        return new Object();
    }
}
