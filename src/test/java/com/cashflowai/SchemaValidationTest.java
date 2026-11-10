package com.cashflowai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** db/schema.sql must match the JPA entities; Hibernate validates it on startup. */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql",
        "spring.jpa.hibernate.ddl-auto=validate",
        "cashflow.llm.api-key=",
        "paypal.enabled=false"
})
class SchemaValidationTest {

    @Test
    void schemaMatchesEntities() {
        // Context startup fails if a table or column is missing or has the wrong type.
    }
}
