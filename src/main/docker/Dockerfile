FROM registry.turacocloud.com/turaco-package/openjdk:17-alpine

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
