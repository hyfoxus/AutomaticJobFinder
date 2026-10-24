package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Ingestion, scoring and digest each keep their own history and live side by side in one database. */
@Testcontainers
class FlywayIsolationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    @Test
    void threeServicesMigrateIntoOneDatabase() throws Exception {
        Properties app = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            app.load(in);
        }
        assertThat(app.getProperty("spring.flyway.schemas")).isEqualTo("digest");

        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("filesystem:../ingestion-service/src/main/resources/db/migration").load().migrate();
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("scoring").defaultSchema("scoring")
                .locations("filesystem:../scoring-service/src/main/resources/db/migration").load().migrate();
        Flyway digest = Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas(app.getProperty("spring.flyway.schemas"))
                .defaultSchema(app.getProperty("spring.flyway.default-schema"))
                .locations("classpath:db/migration").load();
        assertThat(digest.migrate().success).isTrue();

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            assertThat(count(st, "select count(*) from information_schema.tables where table_schema='digest' "
                    + "and table_name in ('sent_assessment','scan_cursor','flyway_schema_history')")).isEqualTo(3);
            assertThat(count(st, "select max(version::int) from scoring.flyway_schema_history")).isEqualTo(3);
            assertThat(count(st, "select count(*) from digest.flyway_schema_history where version = '1'")).isEqualTo(1);
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
