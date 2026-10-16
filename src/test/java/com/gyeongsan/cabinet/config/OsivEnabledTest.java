package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

@SpringBootTest
class OsivEnabledTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.azure.storage.blob.BlobServiceClient blobServiceClient;

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("웹 요청마다 EntityManager 를 열어 두는 OSIV 인터셉터가 등록돼 있다")
    void osivInterceptorIsRegistered() {
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class)).isNotEmpty();
    }
}
