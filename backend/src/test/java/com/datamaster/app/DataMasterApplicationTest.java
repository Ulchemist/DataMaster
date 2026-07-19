package com.datamaster.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "datamaster.home=target/test-datamaster-home")
class DataMasterApplicationTest {
    @Autowired
    private MultipartProperties multipart;

    @Test
    void contextLoadsWithoutApiKeys() {
        // The application must start before the user configures either AI provider.
    }

    @Test
    void largeUploadsAreDiskBufferedAndHaveProtocolHeadroom() {
        assertThat(multipart.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(500));
        assertThat(multipart.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(512));
        assertThat(multipart.getFileSizeThreshold()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(multipart.getLocation()).isNotBlank();
        assertThat(multipart.isResolveLazily()).isTrue();
    }
}
