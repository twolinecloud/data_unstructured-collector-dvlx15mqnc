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
                        `대상 선별 → 파일 확보 → 복호화 → STT → 출력 저장 → 처리 이력 적재(T1·T2·T4)` 까지입니다.
                        STT 텍스트를 외부 서비스로 전송하지 않습니다 — 비식별 커넥터 연동은 아키텍처 변경으로
                        제외됐고, 이 서비스의 역할은 STT 처리와 내부 저장·감시까지입니다.
                        STT 텍스트는 배치 단위 폴더 `{base-dir}/xenon/voice/{execId}/`(접견) ·
                        `{base-dir}/xenon/phone/{execId}/`(전화)에 `.txt` + `.json` 으로 남깁니다(base-dir: 로컬 `C:/k8s` · 배포 `/k8s`).
                        로그 테이블 적재는 **log-collector** API 로만 합니다 — T1(배치) · T2(COLLECT·ANALYZE) · T4(파일별).

                        ### 시연 순서
                        1. `GET /api/v1/voice/status` — 5개 스위치(source·broker·phone·decrypt·stt)가 어느 모드인지 확인
                        2. `GET /api/v1/mock/targets` — 이번에 처리될 대상 미리보기
                        3. `POST /api/v1/voice/batches/daily` — 배치 실행
                        4. 같은 배치를 한 번 더 실행 — `skippedCnt` 로 멱등 동작 확인
                        5. `POST /api/v1/mock/reset` — 초기화 후 반복

                        ### 확인 포인트
                        배치 결과의 `outputDirs` — 이 배치의 STT 텍스트가 놓인 폴더. `steps` — 컬렉터에 남긴 T2 단계
                        (COLLECT·ANALYZE 의 in/out/err). `outcomes[].sttPath` — 파일별 결과 경로.
                        복호화 원본 음성은 성공·실패를 가리지 않고 STT 직후 지웁니다(계획서 5.3-(4)).

                        ### 재처리 시나리오
                        브로커 주소를 잘못 주면(`PUT /api/v1/mock/endpoints/broker?value=http://localhost:9999`)
                        접견 배치가 전건 실패로 T1·T4 에 남고, 주소를 되돌린 뒤 다시 실행하면 실패했던 건만
                        새 EXEC_ID 로 재처리됩니다(멱등 표식은 성공 건에만 남기 때문).

                        > 시뮬레이터 화면: [/voice_collector_simulator.html](/voice_collector_simulator.html)
                        """)
                .license(new License().name("내부 프로젝트 (KCAIS)")));
    }
}
