package it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:liquibase;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml"})
class ContextTest {
    @Test
    void startupMigrationRunsWithoutAnApplicationBeanConsumer() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:liquibase", "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT label FROM dak_migration WHERE id = 1")) {
            assertTrue(rows.next());
            assertEquals("completed", rows.getString(1));
        }
    }
}
