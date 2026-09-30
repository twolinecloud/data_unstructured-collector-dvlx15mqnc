package egovframework.unstructured.collector.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 시뮬레이터 옛 주소 — 2026-09-30 {@code voice_collector_simulator.html} → {@code unstructured_collector_simulator.html}
 * 로 이름을 바꿨다. 즐겨찾기·문서에 남은 옛 주소로 들어와도 새 화면으로 보낸다(로컬 8085 등 서비스 직접 접속).
 * 배포 환경은 admin-fe nginx 가 같은 리다이렉트를 한다.
 */
@Configuration
public class SimulatorRedirectConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/voice_collector_simulator.html", "/unstructured_collector_simulator.html");
    }
}
