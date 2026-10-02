package egovframework.unstructured.collector.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 메타.
 *
 * <p>시연에서 Swagger 가 곧 조작 콘솔이 된다. 그래서 설명에 <b>지금 어떤 모드로 돌고 있는지</b>와
 * <b>무엇을 확인해야 하는지</b>를 적어 둔다 — 화면만 보고도 다음 동작을 정할 수 있어야 한다.</p>
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.version:v1.0}")
    private String version;

    @Bean
    public OpenAPI unstructuredCollectorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("unstructured-collector — 비정형 데이터 수집 서비스")
                .version(version)
                .description("""
                        보라미의 **비정형 데이터**를 수집합니다 — 수용자 음성(접견·통화)을 골라 가져와 복호화하고
                        **STT 텍스트로 만드는** 음성 파이프라인과, 수용자 사진을 XVARM 에서 받아 복호화·저장하고
                        **Admin DB 에 경로를 매핑하는** 이미지 파이프라인(`/api/v1/image/**`)이 있습니다.
                        (2026-09-29 voice-collector 에서 이관 · 명칭 변경)

                        ### 이 서비스의 범위
                        `[수집] 대상 선별 → 파일 확보 → 복호화 → [정제/분석] STT → [적재/전송] 제논(Zenon) 전송 → 처리 이력 적재(T1·T2·T4)` 까지입니다.
                        클라우드 전송·비식별화(커넥터)는 제외됐고(2026-10-01 3단계 복원) 온프레미스 제논으로 넘깁니다.
                        STT 결과는 PV 에 남기지 않습니다 — 제논으로 보낸 뒤 그 건의 임시 파일을 지웁니다(Purge).
                        로그 테이블 적재는 **log-collector** API 로만 합니다 — T1(배치) · T2(COLLECT·ANALYZE·SEND) · T4(파일별).

                        ### admin 연동 — `0. 비정형 배치(admin 연동)`
                        관리자 화면(admin-api)의 비정형(UNSTRUCTURED) 한 행이 이 서비스를 움직입니다(data-collector 와 같은 방식).
                        `POST /internal/schedule/refresh`(설정 즉시 반영 — 기동 시·5분마다 admin 을 다시 읽기도 함) ·
                        `POST /internal/batch/run`(바로 실행) · `POST /internal/batch/reprocess`(긴급 재처리 — 원배치 구간 · 실패 단계부터) ·
                        `GET /internal/batch/status`. 실행은 **비동기**(202 + execId, 실행 중이면 409)이고 스케줄·바로 실행·재처리가 한 잠금을 씁니다.
                        클러스터 안(admin-api)에서만 부릅니다.

                        ### 시연 순서
                        1. `GET /api/v1/voice/status` — 5개 스위치(source·broker·phone·decrypt·stt)가 어느 모드인지 확인
                        2. `GET /api/v1/mock/targets` — 이번에 처리될 대상 미리보기
                        3. `POST /api/v1/voice/batches/daily` — 배치 실행
                        4. 같은 배치를 한 번 더 실행 — `skippedCnt` 로 멱등 동작 확인
                        5. `POST /api/v1/mock/reset` — 초기화 후 반복

                        ### 확인 포인트
                        배치 결과의 `zenon` — STT 결과를 보낸 곳(모드·주소·건수). `steps` — 컬렉터에 남긴 T2 단계
                        (COLLECT·ANALYZE·SEND 의 in/out/err). `outcomes[].sttPath` — 파일별 전송 위치(`zenon:…`).
                        복호화 원본 음성은 성공·실패를 가리지 않고 STT 직후 지웁니다(계획서 5.3-(4)).

                        ### 재처리 시나리오
                        브로커 주소를 잘못 주면(`PUT /api/v1/mock/endpoints/broker?value=http://localhost:9999`)
                        접견 배치가 전건 실패로 T1·T4 에 남고, 주소를 되돌린 뒤 다시 실행하면 실패했던 건만
                        새 EXEC_ID 로 재처리됩니다(멱등 표식은 성공 건에만 남기 때문).

                        > 시뮬레이터 화면: [/unstructured_collector_simulator.html](/unstructured_collector_simulator.html)
                        """)
                .license(new License().name("내부 프로젝트 (KCAIS)")));
    }
}
