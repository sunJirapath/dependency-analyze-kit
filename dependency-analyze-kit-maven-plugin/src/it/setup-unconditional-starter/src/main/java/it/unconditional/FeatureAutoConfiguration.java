package it.unconditional;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FeatureAutoConfiguration {
    @Bean
    FeatureService unconditionalService() {
        return new FeatureService();
    }
}
