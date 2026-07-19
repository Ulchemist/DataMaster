package com.datamaster.app.api;

import com.datamaster.app.service.SpreadsheetImportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/analysis/upload");

    @Test
    void servletUploadRejectionExplainsBothFiveHundredMegabyteLimits() {
        var response = handler.tooLarge(new MaxUploadSizeExceededException(512L * 1024 * 1024), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .contains("单个文件不超过 500MB")
                .contains("文件总计也不超过 500MB");
    }

    @Test
    void logicalFileTotalRejectionIsAlsoHttp413() {
        var exception = new SpreadsheetImportService.UploadLimitExceededException("一次导入总计超过 500MB");
        var response = handler.logicalUploadTooLarge(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(exception.getMessage());
    }
}
