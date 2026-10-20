package audit.child;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChildConfig {
    @Bean public ChildUser childUser(Runnable featureService) { return new ChildUser(featureService); }
    @Bean public Runnable featureService() { return () -> {}; }

    public record ChildUser(Runnable service) {
        public void use() { service.run(); }
    }
}
