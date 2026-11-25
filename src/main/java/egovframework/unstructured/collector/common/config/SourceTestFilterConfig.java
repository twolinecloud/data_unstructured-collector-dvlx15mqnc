package egovframework.unstructured.collector.common.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * 운영 선별 테스트 조건({@link SourceTestFilter})을 기동 때 환경 변수에서 한 번 읽는다.
 *
 * <p>로그 수준으로 상태가 바로 보이게 한다 — 꺼짐 INFO · 적용 WARN(운영에서 눈에 띄게) · 오류 ERROR.</p>
 */
@Log4j2
@Configuration
public class SourceTestFilterConfig {

    @Bean
    public SourceTestFilter sourceTestFilter() {
        SourceTestFilter f = SourceTestFilter.parse(System.getenv(), LocalDateTime.now());
        if (f.invalid()) {
            log.error("[운영 테스트 조건] SOURCE_TEST_* 값 오류 — 보라미 조회를 하지 않는다(배치는 사유와 함께 FAIL). 오류: {} · 받은 값: {}",
                    f.errors(), f.snapshot().get("given"));
        } else if (f.active()) {
            log.warn("[운영 테스트 조건] 적용 — {} · 기존 기준으로 돌리려면 SOURCE_TEST_* 를 비우거나 지우고 재기동", f.describe());
        } else {
            log.info("[운영 테스트 조건] SOURCE_TEST_* 없음 — 기존 조회 기준으로 동작");
        }
        return f;
    }
}
