package com.gyeongsan.cabinet.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 감사 H-5: 실제 재학생 인트라 ID 가 든 {@code src/main/resources/data.sql} 이 jar 와 공개 이미지에 실려 나갔다. 이 테스트는 (1)
 * 그 파일이 빌드 산출물(클래스패스)에 들어가지 않는지, (2) 테스트/데모용으로 새로 만든 시드에는 가짜 데이터만 있는지 확인한다.
 */
class SeedDataSafetyTest {

    /** 가짜 시드에 나와도 되는 작은따옴표 문자열: 가짜 이름·이메일과 열거형 값뿐이다. 그 밖의 값은 모두 실패시킨다(화이트리스트). */
    private static final Pattern FAKE_NAME_OR_EMAIL =
            Pattern.compile("(test|demo)-[a-z0-9-]+(@example\\.test)?");

    private static final Set<String> ALLOWED_PLAIN_LITERALS =
            Set.of("AVAILABLE", "PRIVATE", "LAPISCINE", "ADMIN", "USER", "A", "B", "C", "테스트용");

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private static List<String> literalsOf(Path file) throws IOException {
        List<String> literals = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.stripLeading().startsWith("--")) {
                continue; // 주석은 값이 아니다
            }
            Matcher m = QUOTED.matcher(line);
            while (m.find()) {
                literals.add(m.group(1));
            }
        }
        return literals;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"src/test/resources/data.sql", "scripts/db/seed/demo-seed.sql"})
    @DisplayName("테스트/데모 시드에는 가짜 이름(test-, demo-)·example.test 이메일·열거형 값만 있다")
    void seedContainsOnlyFakeData(String path) throws IOException {
        List<String> literals = literalsOf(Path.of(path));

        assertThat(literals).as(path + " 에 문자열 값이 있어야 한다").isNotEmpty();
        assertThat(literals)
                .as("허용 목록에 없는 값이 있으면 실제 데이터가 섞였을 수 있다")
                .allSatisfy(
                        literal ->
                                assertThat(
                                                FAKE_NAME_OR_EMAIL.matcher(literal).matches()
                                                        || ALLOWED_PLAIN_LITERALS.contains(literal))
                                        .as(
                                                "허용되지 않은 값 길이=%d 시작=%s",
                                                literal.length(), prefix(literal))
                                        .isTrue());
        // 이메일 형태의 값은 전부 example.test 도메인이다.
        assertThat(literals.stream().filter(l -> l.contains("@")))
                .allSatisfy(email -> assertThat(email).endsWith("@example.test"));
    }

    /** 실패 메시지에 값 전체를 싣지 않기 위해 앞 3글자만 보여 준다. */
    private static String prefix(String s) {
        return s.length() <= 3 ? s : s.substring(0, 3) + "…";
    }

    @Test
    @DisplayName("빌드된 메인 리소스(build/resources/main)에 data.sql 이 없다 — 어떤 jar 에도 실리지 않는다")
    void mainResourcesOutputHasNoSeedFile() {
        assertThat(Path.of("build/resources/main/data.sql"))
                .as("processResources 가 data.sql 을 제외해야 한다 (build.gradle)")
                .doesNotExist();
    }

    @Test
    @DisplayName("테스트 클래스패스의 /data.sql 은 가짜 시드이다 (메인의 실제 파일이 아니다)")
    void testClasspathSeedIsTheFakeOne() throws IOException {
        var resource = getClass().getResource("/data.sql");
        assertThat(resource).isNotNull();
        String content = new String(resource.openStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("가짜 시드");
        assertThat(content).contains("test-admin");
    }
}
