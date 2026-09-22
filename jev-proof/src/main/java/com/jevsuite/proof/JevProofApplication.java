package com.jevsuite.proof;

import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.kit.client.TypeSafeJevClient;
import com.jevsuite.proof.api.ProofController;
import com.jevsuite.proof.service.ProofService;
import com.jevsuite.proof.subs.SubtitleFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@SpringBootApplication
public class JevProofApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(JevProofApplication.class, args);
    }

    @Bean
    JevClient jevClient(@Value("${jev.typesafe.base-url}") String baseUrl,
                        @Value("${jev.typesafe.api-key:}") String apiKey,
                        @Value("${jev.typesafe.model:jev-latest}") String model,
                        @Value("${jev.typesafe.timeout-ms:8000}") int timeoutMs) {
        if (apiKey == null || apiKey.isBlank()) {
            // mock：含 "evidence" 命中的要点判 0.9（PASS），否则 0.1（FAIL）
            return new MockJevClient(Map.of("evidence[", 0.9), 0.9);
        }
        return new TypeSafeJevClient(baseUrl, apiKey, model, timeoutMs);
    }

    @Bean
    ProofService proofService(SubtitleFetcher subs, JevClient jev) {
        return new ProofService(subs, jev);
    }

    /** 启动时读 .env（不提交，见 .gitignore）。 */
    static void loadDotEnv() {
        Path p = Path.of(".env");
        if (!Files.exists(p)) return;
        try {
            for (String line : Files.readAllLines(p)) {
                line = line.trim();
                int i = line.indexOf('=');
                if (i > 0 && !line.startsWith("#")) {
                    System.setProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            }
        } catch (IOException ignored) {}
    }
}
