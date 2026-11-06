package com.example.analyzer.service;

import com.example.analyzer.model.AnalyzeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final WebClient webClient;
    private final String apiKey;

    // Gemini REST API base URL
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String MODEL = "gemini-2.0-flash";

    public GeminiService(@Value("${gemini.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(GEMINI_BASE_URL)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)) // 2 MB buffer
                .build();
    }

    /**
     * Zavolá Gemini API, získá shrnutí a seznam úkolů z textu.
     */
    public AnalyzeResponse analyze(String text) {
        String prompt = buildPrompt(text);
        log.debug("Odesílám prompt Gemini (délka: {} znaků)", prompt.length());

        // Sestavení těla požadavku dle Gemini API specifikace
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.3,
                        "maxOutputTokens", 1024
                )
        );

        try {
            // Volání Gemini REST API – zachytíme i HTTP chybové stavové kódy
            JsonNode response = webClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", MODEL, apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResp ->
                            clientResp.bodyToMono(String.class).flatMap(body -> {
                                log.error("Gemini API 4xx chyba: {}", body);
                                return Mono.error(new RuntimeException("Chyba API klíče nebo požadavku: " + body));
                            })
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResp ->
                            clientResp.bodyToMono(String.class).flatMap(body -> {
                                log.error("Gemini API 5xx chyba: {}", body);
                                return Mono.error(new RuntimeException("Gemini server vrátil chybu: " + body));
                            })
                    )
                    .bodyToMono(JsonNode.class)
                    .block();

            return parseResponse(response);

        } catch (WebClientResponseException ex) {
            log.error("WebClient HTTP chyba {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new RuntimeException("Gemini API odpovědělo chybou " + ex.getStatusCode()
                    + ": " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            log.error("Chyba při volání Gemini API", ex);
            throw new RuntimeException("Nepodařilo se kontaktovat Gemini API: " + ex.getMessage(), ex);
        }
    }

    /**
     * Sestaví strukturovaný prompt, který Gemini vrátí ve formátu,
     * který lze snadno zparsovat.
     */
    private String buildPrompt(String text) {
        return """
                Analyzuj následující text. Odpověz POUZE v tomto přesném formátu (bez markdown):
                
                SHRNUTÍ:
                <Shrnutí textu v 2-4 větách>
                
                ÚKOLY:
                - <Úkol 1>
                - <Úkol 2>
                - <Úkol N>
                
                Pokud text neobsahuje žádné úkoly, napiš "- Žádné úkoly nenalezeny".
                
                Text k analýze:
                %s
                """.formatted(text);
    }

    /**
     * Zparsuje odpověď Gemini API do AnalyzeResponse.
     */
    private AnalyzeResponse parseResponse(JsonNode response) {
        if (response == null) {
            return new AnalyzeResponse("Chyba: Nepřišla žádná odpověď od API.", List.of());
        }

        // Extrahujeme text z JSON: candidates[0].content.parts[0].text
        String rawText = "";
        try {
            rawText = response
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text")
                    .asText();
        } catch (Exception e) {
            return new AnalyzeResponse("Chyba při čtení odpovědi: " + e.getMessage(), List.of());
        }

        return extractSummaryAndTasks(rawText);
    }

    /**
     * Rozdělí surový text na shrnutí a seznam úkolů.
     */
    private AnalyzeResponse extractSummaryAndTasks(String rawText) {
        String summary = "";
        List<String> tasks = new ArrayList<>();

        String[] sections = rawText.split("(?m)^ÚKOLY:");
        if (sections.length >= 1) {
            String summarySection = sections[0].replace("SHRNUTÍ:", "").strip();
            summary = summarySection;
        }
        if (sections.length >= 2) {
            String tasksSection = sections[1].strip();
            for (String line : tasksSection.split("\n")) {
                String task = line.strip();
                if (task.startsWith("-")) {
                    tasks.add(task.substring(1).strip());
                }
            }
        }

        return new AnalyzeResponse(summary.isBlank() ? rawText : summary, tasks);
    }
}
