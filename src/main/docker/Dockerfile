# 기반 JDK 는 GA 판이어야 한다. 플랫폼 기본값 openjdk:17-alpine 은 2021-06 조기 접근판(17-ea+14)이라
# java.util.HexFormat 의 fromHexDigits 등 6개가 인스턴스 메서드다(GA 17 은 정적). 스프링 6.2 는 URL 경로의
# %XX 를 풀 때 그 정적 메서드를 불러, 한글 · 공백 · %3A 가 든 경로가 전부 500(IncompatibleClassChangeError)이었다.
# Harbor 미러의 Temurin 17 GA(같은 Alpine · musl)로 바꾼다 — openjdk:17-alpine 으로 되돌리지 말 것.
FROM registry.turacocloud.com/turaco-package/eclipse-temurin:17-jdk-alpine

ARG JAR_FILE
ARG SPRING_PROFILE

ENV TARGET_JAR_FILE ${JAR_FILE}
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom -Dspring.profiles.active=${SPRING_PROFILE} --add-opens java.base/sun.reflect.annotation=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en

RUN mkdir -p /logs
ADD ./target/${TARGET_JAR_FILE} /${TARGET_JAR_FILE}
VOLUME /tmp /logs
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /${TARGET_JAR_FILE}"]
