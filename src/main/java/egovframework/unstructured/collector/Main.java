package egovframework.unstructured.collector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import egovframework.unstructured.collector.image.config.ImageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import egovframework.unstructured.collector.common.config.VoiceProperties;

import java.net.URI;
import java.util.TimeZone;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

/**
 * 비정형 음성(접견·통화) 수집 서비스 진입점.
 *
 * <p><b>이 서비스의 범위</b>: 보라미에서 음성 파일을 골라내 가져오고, 복호화한 뒤
 * <b>STT 텍스트로 만들고 처리 이력을 남기는 것까지</b>다. STT 텍스트를 외부로 전송하지 않는다
 * (비식별 커넥터 연동은 아키텍처 변경으로 제외). 로그 적재는 로그 컬렉터 API 로만 한다.</p>
 *
 * <p><b>배치 주체</b>: EXEC_ID 작업코드 {@code UNS}(비정형 — 음성 · 이미지) 로 배치를 연다.
 * {@code STR}(정형)은 데이터 수집 서비스, {@code EXT}(외부)는 외부 연계 수집 서비스가 쓴다.</p>
 */
@EnableScheduling
@EnableConfigurationProperties({VoiceProperties.class, ImageProperties.class,
        egovframework.unstructured.collector.common.transfer.ZenonProperties.class,
        egovframework.unstructured.collector.batch.UnstructuredBatchProperties.class,
        egovframework.unstructured.collector.mock.MockDataProperties.class})
@SpringBootApplication
public class Main {

    @Value("${service-path}")
    private String servicePath;

    private static final String PROPERTIES = "spring.config.location=classpath:/application.yml";

    public static void main(String[] args) {
        // DB 시간 표준 = KST(Asia/Seoul). 로그 컬렉터의 시각 컬럼이 timestamp without time zone 이라
        // 오프셋을 담지 않는다 — 넣는 쪽이 무슨 시계를 봤는지가 값의 전부다. 로그 컬렉터와 다른
        // 시계를 보면 같은 배치의 T1·T4 가 9시간 어긋난다.
        //
        // run() 앞에서 잡는다. 커넥션 풀·로그 appender 처럼 기동 중에 타임존을 붙잡는 것들보다
        // 먼저여야 전부 같은 시계를 본다. @PostConstruct 는 그 순서를 보장하지 못한다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        new SpringApplicationBuilder(Main.class).properties(PROPERTIES).run(args);
    }

    @Bean
    public CorsFilter corsFilter() {
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        final CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        for (String m : new String[]{"OPTIONS", "HEAD", "GET", "PUT", "POST", "DELETE", "PATCH"}) {
            config.addAllowedMethod(m);
        }
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Bean
    RouterFunction<ServerResponse> routerFunction() {
        return route(GET("/swagger"), req ->
                ServerResponse.temporaryRedirect(URI.create(servicePath + "/swagger-ui.html")).build());
    }

    @Bean
    ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}
