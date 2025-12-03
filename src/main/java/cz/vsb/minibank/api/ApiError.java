package cz.vsb.minibank.api;

// Простой DTO для ошибок REST API
public record ApiError(String code, String message) { }
