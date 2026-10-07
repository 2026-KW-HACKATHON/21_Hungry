package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.kw.knowone.encounter.processing.AiProcessingPort;
import com.kw.knowone.encounter.processing.OpenAiProcessingAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("prod")
@SpringBootTest
class ProductionContextTests {
    private static final Path STORAGE;
    static { try { STORAGE=Files.createTempDirectory("production-context-"); } catch(Exception failure) { throw new ExceptionInInitializerError(failure); } }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url",()->environment("DB_URL",
                "jdbc:postgresql://localhost:54329/hungry?currentSchema=hungry_test"));
        values.add("spring.datasource.username",()->environment("DB_USERNAME","hungry"));
        values.add("spring.datasource.password",()->environment("DB_PASSWORD","hungry_local"));
        values.add("spring.flyway.default-schema",()->"hungry_test");
        values.add("spring.flyway.schemas",()->"hungry_test");
        values.add("app.storage.local-root",()->STORAGE.toString());
        values.add("app.preview.signing-secret",()->"production-context-test-secret-32-bytes");
        values.add("app.cors.allowed-origins",()->"https://frontend.example");
        values.add("app.scheduling.enabled",()->"false");
    }

    private static String environment(String name,String fallback) {
        String value=System.getenv(name);
        return value==null||value.isBlank()?fallback:value;
    }

    @Autowired AiProcessingPort adapter;

    @Test void productionProfileCreatesTheRealOpenAiAdapter() {
        assertInstanceOf(OpenAiProcessingAdapter.class,adapter);
    }
}
