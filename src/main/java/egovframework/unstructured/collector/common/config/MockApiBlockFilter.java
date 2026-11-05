package egovframework.unstructured.collector.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 개발 · 로컬이 아닌 프로필에서 시뮬레이터 · 장애 주입 API 를 막는다.
 *
 * <p><b>왜 필요한가</b>: {@code PUT /api/v1/voice/faults/*} · {@code PUT /api/v1/mock/modes/*} ·
 * {@code PUT /api/v1/mock/endpoints/broker} 는 호출 한 번으로 실제 배치를 실패시킨다. 예전에는 인그레스에서
 * {@code /api/v1/mock/**} 를 막는다는 전제만 있었고, {@code /api/v1/voice/faults} 는 그 경로 밖이었다.</p>
 *
 * <p><b>왜 빈을 없애지 않고 경로를 막나</b>: {@code PerfRunService} 가 {@code VoiceMockController} 를 주입받는다.
 * 컨트롤러에 {@code @Profile} 을 걸면 운영 기동이 깨질 수 있다. 경로 필터는 빈 구성을 건드리지 않는다.</p>
 *
 * <p><b>왜 prod 만이 아니라 '개발 · 로컬 외 전부'인가</b>: 프로필 이름을 빠뜨리거나 새 환경이 생겨도 닫힌 쪽으로 간다.
 * 프로필을 지정하지 않으면 {@code spring.profiles.default=local} 이라 그대로 열린다.</p>
 */
@Log4j2
@Component
@Profile("!dev & !local")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MockApiBlockFilter extends OncePerRequestFilter {

    /** 앞부분이 이 경로와 같거나 이 경로 아래면 막는다. */
    static final List<String> BLOCKED_PATHS = List.of("/api/v1/mock", "/api/v1/voice/faults");

    private static final String BODY = "{\"message\":\"운영 환경에서는 제공하지 않는 API 입니다\"}";

    private final UrlPathHelper pathHelper = new UrlPathHelper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 두 값을 다 본다 — 앞은 인코딩 · 세미콜론 · 겹슬래시를 푼 값이고, 뒤는 서블릿 컨테이너가 '..' 까지 정규화한 값이다.
        String path = pathHelper.getPathWithinApplication(request);
        if (!isBlocked(path) && !isBlocked(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("[Security] 시뮬레이터 API 차단 — {} {}", request.getMethod(), path.replaceAll("[\\r\\n]", "_"));
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }

    static boolean isBlocked(String path) {
        if (path == null) {
            return false;
        }
        return BLOCKED_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }
}
