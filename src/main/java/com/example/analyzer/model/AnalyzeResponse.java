package com.example.analyzer.model;

import java.util.List;

public record AnalyzeResponse(String summary, List<String> tasks) {}
