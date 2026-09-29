package egovframework.unstructured.collector.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 외부 호출용 RestTemplate.
 *
 * <p><b>JDK {@link HttpClient} 기반으로 만든다.</b> 기본 {@code SimpleClientHttpRequestFactory}
 * (=HttpURLConnection)는 <b>PATCH 를 지원하지 않는데</b>, 로그 컬렉터의 T1·T2 종료 갱신이
 * 전부 PATCH 다. 같은 함정을 이미 겪은 사내 서비스들이 전용 팩토리로 해결했고, 여기서도 같은 선택을 한다
 * (추가 의존성 없음).</p>
 *
 * <p>JSON 변환은 앱 공용 {@link ObjectMapper} 를 그대로 써서 날짜 포맷을 로그 컬렉터와 맞춘다.</p>
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate voiceRestTemplate(ObjectMapper objectMapper, VoiceProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        // 가장 오래 걸리는 호출(STT)에 맞춘다 — 오디오 한 건이 수 분 걸릴 수 있다.
        factory.setReadTimeout(Duration.ofSeconds(Math.max(props.stt().timeoutSec(), 60)));

        RestTemplate rt = new RestTemplate(factory);
        rt.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        rt.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        return rt;
    }
}
