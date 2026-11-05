package com.cashflowai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** db/schema.sql (used on Postgres) must match the JPA entities; Hibernate validates it on startup. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:schemacheck;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.defer-datasource-initialization=false",
        "cashflow.llm.api-key=",
        "paypal.enabled=false"
})
class SchemaValidationTest {

    @Test
    void schemaMatchesEntities() {
        // Context startup fails if a table or column is missing or has the wrong type.
    }
}
