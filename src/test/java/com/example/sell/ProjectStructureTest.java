package com.example.sell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStructureTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    @Test
    void shouldUseClearLayeredPackages() throws IOException {
        List<Path> javaFiles;
        try (var stream = Files.walk(MAIN_JAVA)) {
            javaFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        assertTrue(Files.isDirectory(MAIN_JAVA.resolve(Path.of("com", "example", "sell", "entity"))));
        assertTrue(Files.isDirectory(MAIN_JAVA.resolve(Path.of("com", "example", "sell", "dto"))));
        assertTrue(Files.isDirectory(MAIN_JAVA.resolve(Path.of("com", "example", "sell", "vo"))));
        assertTrue(Files.isDirectory(MAIN_JAVA.resolve(Path.of("com", "example", "sell", "enums"))));
        assertTrue(Files.isDirectory(MAIN_JAVA.resolve(Path.of("com", "example", "sell", "service", "impl"))));

        for (Path javaFile : javaFiles) {
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            assertFalse(content.contains("com.example.sell.domain.pojo"), javaFile.toString());
            assertFalse(content.contains("com.example.sell.domain.Dto"), javaFile.toString());
            assertFalse(content.contains("com.example.sell.domain.vo"), javaFile.toString());
            assertFalse(content.contains("com.example.sell.domain.enums"), javaFile.toString());
            assertFalse(content.contains("com.example.sell.service.Imp"), javaFile.toString());
        }
    }
}
