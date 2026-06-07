package com.mytext.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:flyway_schema_validation_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=false"
})
class FlywaySchemaValidationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayMigrationSupportsHibernateValidate() throws Exception {
        assertThat(flyway.info().current()).isNotNull();

        try (var connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(null, null, "rag_evaluation_suite", null)) {
            assertThat(tables.next()).isTrue();
        }
    }
}
