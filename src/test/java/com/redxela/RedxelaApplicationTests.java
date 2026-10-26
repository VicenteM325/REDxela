package com.redxela;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifica que el contexto de Spring arranca completo. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ctx;DB_CLOSE_DELAY=-1")
class RedxelaApplicationTests {

    @Test
    void contextoArranca() {
    }
}
