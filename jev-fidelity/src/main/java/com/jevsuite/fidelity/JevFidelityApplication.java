package com.jevsuite.fidelity;

import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.kit.client.TypeSafeJevClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@SpringBootApplication
public class JevFidelityApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(JevFidelityApplication.class, args);
    }

    @Bean
    JevClient jevClient(@Value("${jev.typesafe.base-url}") String baseUrl,
                        @Value("${jev.typesafe.api-key:}") String apiKey,
                        @Value("${jev.typesafe.model:jev-latest}") String model,
                        @Value("${jev.typesafe.timeout-ms:8000}") int timeoutMs) {
        if (apiKey == null || apiKey.isBlank()) {
            // mock：有候选对齐 → is_fact 0.9（Choice 固定回 criteria 首项 preserved）
            return new MockJevClient(Map.of("edited_candidate[", 0.9), 0.2);
        }
        return new TypeSafeJevClient(baseUrl, apiKey, model, timeoutMs);
    }

    /** 启动时读 .env（不提交，见 .gitignore）。 */
    static void loadDotEnv() {
        for (String dir : new String[]{".", "jev-fidelity"}) {
            Path p = Path.of(dir, ".env");
            if (!Files.exists(p)) continue;
            try {
                for (String line : Files.readAllLines(p)) {
                    line = line.trim();
                    int i = line.indexOf('=');
                    if (i > 0 && !line.startsWith("#")) {
                        System.setProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                    }
                }
                return;
            } catch (IOException ignored) {}
        }
    }
}
