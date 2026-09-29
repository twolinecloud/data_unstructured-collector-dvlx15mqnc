package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ESB 가 떨궈 준 파일의 이름을 어떻게 알아볼 것인가.
 *
 * <p><b>표준 A 에 규칙이 두 개 병기되어 있다</b>(3.8).</p>
 * <ul>
 *   <li>"업무시스템에서 사용하는 파일명을 그대로 사용한다" → {@link Policy#ORIGINAL}</li>
 *   <li>{@code 'R'_인터페이스ID(5)_송신기관도메인_수신기관도메인_송신일시(17)+랜덤난수(5).DAT}
 *       → {@link Policy#ESB_DAT}</li>
 * </ul>
 *
 * <p>어느 쪽인지 ESB 담당자 확인이 필요하다(계획서 Q7). <b>이 선택이 중요한 이유</b>는
 * {@code ESB_DAT} 규칙에는 <b>업무 키가 하나도 들어가지 않기</b> 때문이다 — 파일만 보고는
 * 어느 접견·통화 건인지 알 수 없어, 별도 매핑 수단(동봉 메타 파일, 도착 순서, 크기 대조 등)이
 * 필요해진다. 게다가 확장자가 {@code .DAT} 라 원본 포맷(m4a/mp4/wav)도 사라진다.</p>
 *
 * <p>기본값은 {@code ORIGINAL} 이다. 확정 전까지는 매핑이 성립하는 쪽을 쓴다.</p>
 */
@Component
public class EsbFileNamingPolicy {

    public enum Policy {
        /** 업무 파일명 그대로 — 대상과 1:1 매핑이 성립한다. */
        ORIGINAL,
        /** 표준 A 의 .DAT 규약 — 업무 키가 없어 별도 매핑 수단이 필요하다. */
        ESB_DAT
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 대상에 대응하는 수신 파일명을 만든다(Mock 생성·탐색 양쪽에서 같은 규칙을 쓴다). */
    public String expectedFileName(VoiceTarget target, Policy policy) {
        if (policy == Policy.ESB_DAT) {
            return datName(target, "BOR", "KAI", LocalDateTime.now());
        }
        return target.srcFileName();
    }

    /**
     * 표준 A 의 {@code .DAT} 파일명을 만든다.
     *
     * @param sndDomain 송신기관 도메인(예: 보라미). 실제 코드는 부록2 확인 필요(Q2)
     * @param rcvDomain 수신기관 도메인(우리)
     */
    public String datName(VoiceTarget target, String sndDomain, String rcvDomain, LocalDateTime at) {
        int rand = ThreadLocalRandom.current().nextInt(0, 100_000);
        return "R_%s_%s_%s_%s%05d.DAT".formatted(
                ifId(target), sndDomain, rcvDomain, TS.format(at), rand);
    }

    /** 인터페이스ID 5자리 자리표시자 — 실제 값은 미확정(Q2). */
    private String ifId(VoiceTarget target) {
        return switch (target.kind()) {
            case MEET -> "IF001";
            case PHONE -> "IF002";
        };
    }
}
