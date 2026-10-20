package it;

import audit.factory.FactoryInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(properties = "feature.enabled=true")
@ContextConfiguration(initializers = FactoryInitializer.class)
class ContextTest {
    @Autowired ApplicationContext context;

    @Test
    void nestedFactoryUsesFeatureService() {
        context.getBean(it.unconditional.FactoryConfig.Client.class).use();
    }
}
