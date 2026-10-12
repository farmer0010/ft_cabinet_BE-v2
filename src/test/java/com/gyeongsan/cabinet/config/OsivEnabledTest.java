package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

/**
 * 감사 H-11 의 전제: 운영 설정에서 Open-Session-In-View(OSIV)가 실제로 켜져 있다. {@code spring.jpa.open-in-view} 를
 * 어디에서도 지정하지 않아 Boot 기본값(true)이 적용된다. 이 테스트는 그 사실을 고정한다 — 의도적으로 끈다면 이 테스트와 {@code
 * LentSwapOsivMysqlTest} 를 함께 고쳐야 한다.
 */
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
