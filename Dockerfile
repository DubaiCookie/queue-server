## OpenJDK 21 사용
FROM openjdk:21-jdk-slim

# 컨테이너 내의 작업 디렉토리 설정
WORKDIR /app

COPY build/libs/*.jar ./app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]