package it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ContextTest {
    @Test
    void contextLoads() {
        assertEquals("custom", System.getProperty("dak.runner"));
        assertEquals("true", System.getProperty("dak.enabled"));
        assertEquals("dak-it", System.getProperty("spring.profiles.active"));
    }
}
