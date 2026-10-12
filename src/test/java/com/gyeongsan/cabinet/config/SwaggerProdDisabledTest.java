package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * 운영 프로필에서 Swagger UI 와 OpenAPI JSON 이 실제로 사라지는지 확인한다. 운영 설정 파일(application.yml +
 * application-prod.yml)을 그대로 읽어 springdoc 자동구성에 적용하고, 실제 MockMvc 요청으로 상태 코드를 본다. 설정 파일 문자열만 검사하면
 * springdoc 프로퍼티 이름이 틀려도 통과하기 때문에 동작으로 검증한다.
 */
class SwaggerProdDisabledTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");
    private static final Path PROD_YML = Path.of("src/main/resources/application-prod.yml");

    @RestController
    static class PingController {
        @GetMapping("/v4/ping")
        String ping() {
            return "pong";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SampleApiConfig {
        @org.springframework.context.annotation.Bean
        PingController pingController() {
            return new PingController();
        }
    }

    private WebApplicationContextRunner runner(boolean prodProfile) {
        return new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                DispatcherServletAutoConfiguration.class,
                                WebMvcAutoConfiguration.class,
                                HttpMessageConvertersAutoConfiguration.class,
                                JacksonAutoConfiguration.class,
                                SpringDocConfiguration.class,
                                SpringDocConfigProperties.class,
                                SpringDocWebMvcConfiguration.class,
                                MultipleOpenApiSupportConfiguration.class,
                                SwaggerConfig.class,
                                SwaggerUiConfigProperties.class,
                                SwaggerUiOAuthProperties.class))
                .withUserConfiguration(SampleApiConfig.class)
                .withInitializer(
                        context -> {
                            try {
                                // 프로필 설정이 기본 설정보다 우선하도록 먼저 넣는다.
                                if (prodProfile) {
                                    for (PropertySource<?> source :
                                            new YamlPropertySourceLoader()
                                                    .load(
                                                            "prod-application-yml",
                                                            new FileSystemResource(PROD_YML))) {
                                        context.getEnvironment()
                                                .getPropertySources()
                                                .addLast(source);
                                    }
                                }
                                for (PropertySource<?> source :
                                        new YamlPropertySourceLoader()
                                                .load(
                                                        "main-application-yml",
                                                        new FileSystemResource(MAIN_YML))) {
                                    context.getEnvironment().getPropertySources().addLast(source);
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    private static MockMvc mockMvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
    }

    @Test
    @DisplayName("기본 프로필에서는 개발 편의를 위해 OpenAPI JSON 과 Swagger UI 가 열려 있다 (대조군)")
    void enabledByDefault() {
        runner(false)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            MockMvc mvc = mockMvc(context.getSourceApplicationContext());
                            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
                            mvc.perform(get("/swagger-ui.html"))
                                    .andExpect(
                                            result ->
                                                    assertThat(result.getResponse().getStatus())
                                                            .as("UI 로 리다이렉트되거나 200")
                                                            .isIn(200, 302));
                            mvc.perform(get("/v4/ping")).andExpect(status().isOk());
                        });
    }

    @Test
    @DisplayName("prod 프로필에서는 OpenAPI JSON 과 Swagger UI 경로가 모두 404 이고 관련 빈도 만들어지지 않는다")
    void disabledInProd() {
        runner(true)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            MockMvc mvc = mockMvc(context.getSourceApplicationContext());
                            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
                            mvc.perform(get("/v3/api-docs/swagger-config"))
                                    .andExpect(status().isNotFound());
                            mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
                            mvc.perform(get("/swagger-ui/index.html"))
                                    .andExpect(status().isNotFound());
                            // 일반 API 는 그대로 동작한다.
                            mvc.perform(get("/v4/ping")).andExpect(status().isOk());

                            assertThat(context)
                                    .doesNotHaveBean(
                                            org.springdoc.webmvc.api.OpenApiWebMvcResource.class);
                        });
    }
}
