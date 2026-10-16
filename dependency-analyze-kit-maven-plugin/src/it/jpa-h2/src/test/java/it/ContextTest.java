package it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ContextTest {
    @Autowired
    PersonRepository repository;

    @Test
    void saveAndFind() {
        Person saved = repository.save(new Person("alice"));
        assertEquals("alice", repository.findById(saved.getId()).orElseThrow().getName());
    }
}
