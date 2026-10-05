package egovframework.unstructured.collector.mock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 대시보드 더미 일 단위 자동 생성 스케줄러 — 상태 · ON/OFF · 즉시 실행 · N일 경과 시뮬레이션.
 *
 * <p>dev/local 프로필에서만 빈이 생긴다(운영 404). 공개 프록시에서는 {@code /api/v1/mock/**} 를 막는다.</p>
 */
@Tag(name = "9. 시뮬레이터 (Mock 전용)",
        description = "시연 반복을 위한 초기화·미리보기. 운영에서는 /api/v1/mock/** 를 차단한다")
@Log4j2
@RestController
@Profile({"dev", "local"})
@RequestMapping(value = "/api/v1/mock/auto-generator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AutoGeneratorController {

    private final MockDataScheduler scheduler;

    @Operation(summary = "일 단위 자동 생성 — 상태",
            description = """
                    켜짐 여부(`enabled`) · 그 값의 출처(`source` CONFIG=설정 · UI=화면에서 바꿈) · cron(한국 시각) · 다음 실행 ·
                    유형 · 유형별 건수 · 장애 건수 · 보존 일수 · 마지막 실행 결과.
                    처음 값은 `unstructured.mock.daily.enabled`(기본 **false**). 화면에서 바꾸면 PV `{ROOT}/mock/auto_generator.json` 에 남는다.
                    """)
    @GetMapping("/status")
    public Map<String, Object> status() {
        return scheduler.status();
    }

    @Operation(summary = "일 단위 자동 생성 — ON/OFF",
            description = "켜면 매일 cron(기본 01:30)에 어제 날짜로 대시보드 더미(DMY)를 추가하고 보존 일수(기본 7일) 지난 DMY 를 지운다. "
                    + "설정값과 다른 쪽이면 PV 파일에 남겨 재배포 뒤에도 유지하고, 설정값으로 되돌리면 파일을 지운다.")
    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestParam boolean enabled) {
        return scheduler.setEnabled(enabled);
    }

    @Operation(summary = "일 단위 자동 생성 — 즉시 1회",
            description = """
                    새벽을 기다리지 않고 지금 한 번 — `targetDate`(비우면 어제) 치 대시보드 더미를 만들고, 그 다음 날을 실행일로 보고
                    보존 일수 이전 DMY 를 지운다(새벽 스케줄과 같은 일). ON/OFF 와 상관없이 돈다.
                    응답 `days` — 대상일 앞뒤 날짜별 DMY 건수(접견 · 전화 행 · 이미지 수용자).
                    """)
    @PostMapping("/trigger")
    public Map<String, Object> trigger(
            @Parameter(description = "생성 대상일 yyyy-MM-dd — 비우면 어제. 미래는 안 된다", example = "2026-10-04")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        return scheduler.trigger(targetDate);
    }

    @Operation(summary = "일 단위 자동 생성 — N일 경과 시뮬레이션",
            description = """
                    가상 실행일을 N일 앞당겨 시작해 하루씩 **N+1번**(0일째 + N일 경과) 돈다 — 대상일 `오늘-N-1 … 어제` 를 만들고 매번 보존 일수 이전을 지운다.
                    N = 보존 일수(기본 7)면 첫날 만든 `오늘-8` 치는 마지막 회차에서 지워지고 `오늘-7 … 어제` 7일 치만 남아야 한다.
                    응답 `checks` — 날짜별 전 · 후 건수와 기대(PURGED · KEPT) · `verdict` PASS | FAIL | NO_PURGE.
                    보존 일수 이전의 기존 DMY 도 같이 지워진다(새벽 스케줄과 같다).
                    """)
    @PostMapping("/simulate")
    public Map<String, Object> simulate(
            @Parameter(description = "경과 일수 1~14 — 비우면 보존 일수", example = "7")
            @RequestParam(required = false) Integer days) {
        return scheduler.simulate(days);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        log.warn("[Dummy:daily] 잘못된 요청 — {}", e.getMessage());
        return Map.of("error", "BAD_REQUEST", "message", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(DummyDataService.DashboardDisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> disabled(DummyDataService.DashboardDisabledException e) {
        return Map.of("error", "DASHBOARD_DISABLED", "message", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(MockDataScheduler.BusyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> busy(MockDataScheduler.BusyException e) {
        return Map.of("error", "BUSY", "message", String.valueOf(e.getMessage()));
    }
}
