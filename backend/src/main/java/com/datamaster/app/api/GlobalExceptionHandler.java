package com.datamaster.app.api;

import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.AiInsightService;
import com.datamaster.app.service.ProviderConfigService;
import com.datamaster.app.service.SpreadsheetImportService;
import com.datamaster.app.service.SyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AnalysisService.AnalysisNotFoundException.class)
    public ResponseEntity<ApiError> notFound(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, ProviderConfigService.ProviderNotConfiguredException.class,
            MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> badRequest(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, safeMessage(ex, "请求内容不正确"), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.CONTENT_TOO_LARGE,
                "上传内容超过限制：单个文件不超过 500MB，一次导入的文件总计也不超过 500MB", request);
    }

    @ExceptionHandler(SpreadsheetImportService.UploadLimitExceededException.class)
    public ResponseEntity<ApiError> logicalUploadTooLarge(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage(), request);
    }

    @ExceptionHandler(AiInsightService.AiCallException.class)
    public ResponseEntity<ApiError> aiFailure(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(SyncService.RemoteSyncException.class)
    public ResponseEntity<ApiError> syncFailure(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "处理失败：" + safeMessage(ex, "未知错误"), request);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                message, request.getRequestURI()));
    }

    private static String safeMessage(Exception ex, String fallback) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return fallback;
        // Defensive redaction in case a third-party HTTP client embeds authorization values.
        return message.replaceAll("(?i)(api[-_ ]?key|authorization|bearer)\\s*[:=]?\\s*[^,;\\s]+", "$1 [已隐藏]");
    }

    public record ApiError(Instant timestamp, int status, String error, String message, String path) {
    }
}
