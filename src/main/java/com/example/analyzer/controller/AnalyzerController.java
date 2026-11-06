package com.example.analyzer.controller;

import com.example.analyzer.model.AnalyzeRequest;
import com.example.analyzer.model.AnalyzeResponse;
import com.example.analyzer.service.GeminiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AnalyzerController {

    private final GeminiService geminiService;

    public AnalyzerController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    /**
     * POST /api/analyze
     * Přijme text, pošle ho Gemini a vrátí shrnutí + úkoly.
     */
    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@RequestBody AnalyzeRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new AnalyzeResponse("Text nesmí být prázdný.", java.util.List.of()));
        }
        AnalyzeResponse response = geminiService.analyze(request.text());
        return ResponseEntity.ok(response);
    }
}
