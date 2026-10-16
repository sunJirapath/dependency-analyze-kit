package it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ContextTest {
    @Autowired
    ReportService reportService;

    @Test
    void contextLoads() {
        assertNotNull(reportService);
    }
}
