package audit.factory;

import it.unconditional.FactoryConfig;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class FactoryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var registry = (BeanDefinitionRegistry) context.getBeanFactory();
        // Register products before their factories to exercise order-independent root expansion.
        var client = new RootBeanDefinition();
        client.setFactoryBeanName("factoryConfig");
        client.setFactoryMethodName("client");
        client.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        registry.registerBeanDefinition("client", client);
        var factory = new RootBeanDefinition();
        factory.setFactoryBeanName("projectConfig");
        factory.setFactoryMethodName("factoryConfig");
        registry.registerBeanDefinition("factoryConfig", factory);
        registry.registerBeanDefinition("projectConfig", new RootBeanDefinition(ProjectConfig.class));
    }

    public static class ProjectConfig {
        public FactoryConfig factoryConfig() { return new FactoryConfig(); }
    }
}
