package xyz.eginez.quantumpapers;

import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import jakarta.inject.Inject;

import java.util.Optional;

@MicronautTest
class QuantumPapersTest {

    @Inject
    EmbeddedApplication<?> application;

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

    @Test
    void tesOne() throws Exception {
        var s = new Server();
        var res = s.index(Optional.of("src/test/resources/papers.txt"), Optional.of(true));
        Assertions.assertTrue(true);
    }

}
