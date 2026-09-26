package com.callverse.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real PostgreSQL with the real schema.
 *
 * <p><strong>Why a container and not H2.</strong> The schema is Flyway-managed PostgreSQL DDL using
 * {@code JSONB}, {@code TEXT[]}, partial indexes, {@code gen_random_uuid()} and {@code vector(384)}.
 * H2's PostgreSQL compatibility mode supports none of that convincingly, so a test against it would
 * be verifying a database we do not deploy. Any agreement it reported would be meaningless.
 *
 * <p><strong>Why the pgvector image and not plain {@code postgres:16}.</strong> {@code V1__init.sql}
 * runs {@code CREATE EXTENSION vector} and builds an HNSW index on {@code kb_chunk.embedding}. The
 * stock Postgres image has no such extension and the migration fails on line 2, so the image is
 * part of the contract, not an optimisation.
 *
 * <p><strong>No Neon credentials, no .env.</strong> {@code @DynamicPropertySource} overrides the
 * datasource and Flyway URLs after the container has a port, and supplies the {@code jwt.secret}
 * that {@code application.yml} deliberately gives no default. The suite therefore runs on any
 * machine with Docker and nothing else.
 *
 * <p>The container is {@code static}, so one instance is shared by every subclass for the whole
 * JVM run rather than being started per test class.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPersistenceTest {

    /**
     * The {@code jwt.secret} every Spring test context signs and verifies with.
     *
     * <p>At least 64 bytes, because the issuer pins HS512 and jjwt refuses a shorter key for it.
     * Public so that test helpers mint tokens with the same key instead of keeping a copy that can
     * drift.
     */
    public static final String TEST_JWT_SECRET =
            "test-only-hs512-signing-key-not-used-for-anything-real-0123456789abcdef";

    /**
     * The {@code internal.service-key} every Spring test context accepts on {@code /internal/**}.
     * At least 32 bytes, the startup minimum.
     */
    public static final String TEST_INTERNAL_SERVICE_KEY = "test-only-internal-service-key-0123456789abcdef";

    @SuppressWarnings("resource") // lifecycle is managed by Testcontainers' JVM shutdown hook
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg16")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("callverse")
                    .withUsername("callverse_test")
                    .withPassword("callverse_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Flyway normally targets Neon's direct endpoint while the app uses the pooled one.
        // A single container has no such split, so both point at the same URL here.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // application.yml gives jwt.secret no default on purpose, so that the application refuses
        // to start on a well-known key. Tests must therefore supply one.
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("internal.service-key", () -> TEST_INTERNAL_SERVICE_KEY);
    }
}
