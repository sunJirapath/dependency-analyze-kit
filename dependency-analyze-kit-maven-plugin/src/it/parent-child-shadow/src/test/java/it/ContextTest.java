package it;

import audit.child.ChildConfig;
import io.github.sunjirapath.dak.probe.ProbeInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@SpringBootTest(properties = "feature.enabled=true")
class ContextTest {
    @Autowired ConfigurableApplicationContext parent;

    @Test
    void childUsesAParentBean() {
        try (var child = new AnnotationConfigApplicationContext()) {
            child.setId(parent.getId()); // display IDs are not context identities
            child.setParent(parent);
            new ProbeInitializer().initialize(child);
            child.register(ChildConfig.class);
            child.refresh();
            child.getBean(ChildConfig.ChildUser.class).use();
        }
    }
}
