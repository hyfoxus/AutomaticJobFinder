package com.gnemirko.jobapplier.scoring;

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

/** A history table already sitting in public (ingestion's) must not disturb the scoring migration. */
@Testcontainers
class FlywayIsolationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    @Test
    void scoringMigratesNextToAnExistingPublicHistory() throws Exception {
        // Use the service's real settings, not hand-copied ones.
        Properties app = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            app.load(in);
        }
        String schema = app.getProperty("spring.flyway.schemas");
        String defaultSchema = app.getProperty("spring.flyway.default-schema");
        assertThat(schema).isEqualTo("scoring");

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            int ingestionMigrations = Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                    .locations("filesystem:../ingestion-service/src/main/resources/db/migration")
                    .load().migrate().migrationsExecuted;
            assertThat(ingestionMigrations).isPositive();
            int publicBefore = count(st, "select count(*) from public.flyway_schema_history");

            Flyway scoring = Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                    .schemas(schema).defaultSchema(defaultSchema)
                    .locations("classpath:db/migration").load();
            assertThat(scoring.migrate().success).isTrue();

            assertThat(count(st, "select count(*) from information_schema.tables where table_schema='scoring' "
                    + "and table_name in ('assessments','llm_usage','ingest_cursor','flyway_schema_history')")).isEqualTo(4);
            assertThat(count(st, "select count(*) from public.flyway_schema_history")).isEqualTo(publicBefore);
            assertThat(count(st, "select count(*) from scoring.flyway_schema_history where version = '1'")).isEqualTo(1);
            assertThat(count(st, "select count(*) from scoring.flyway_schema_history where version = '2'")).isEqualTo(1);
            assertThat(count(st, "select max(version::int) from scoring.flyway_schema_history")).isEqualTo(3);
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
