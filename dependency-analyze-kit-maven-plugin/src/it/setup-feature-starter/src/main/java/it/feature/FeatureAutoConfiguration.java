package it.feature;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(name = "feature.enabled", havingValue = "true")
public class FeatureAutoConfiguration {
    @Bean
    FeatureService featureService() {
        return new FeatureService();
    }
}
