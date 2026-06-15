# D2: tia CLI 이미지 — impact-only(JDK17 + fat-jar). 무거운 convert/index 는 범위 밖(§D2).
# 빌드 전제: ./gradlew :tia-cli:shadowJar (호스트/CI에서 tia.jar 생성) 후 컨텍스트=레포 루트.
FROM eclipse-temurin:17-jre
LABEL org.opencontainers.image.source="https://github.com/baekchangjoon/test-impact-analysis" \
      org.opencontainers.image.description="Test-Impact-Analysis CLI (impact/report/convert/index/flaky)" \
      org.opencontainers.image.licenses="MIT AND Apache-2.0 AND BSD-3-Clause AND EPL-2.0"
COPY tia-cli/build/libs/tia.jar /opt/tia/tia.jar
# 라이선스 고지(OCI 관례 /licenses): 자체 MIT + 번들 서드파티 NOTICE/전문
COPY LICENSE THIRD-PARTY-NOTICES.md /licenses/
COPY licenses/ /licenses/
# 엔트리포인트 래퍼: `docker run img <args>` → `java -jar tia.jar <args>` (설계 §D2 실행 계약)
RUN printf '#!/bin/sh\nexec java -jar /opt/tia/tia.jar "$@"\n' > /usr/local/bin/tia \
    && chmod +x /usr/local/bin/tia
WORKDIR /work
ENTRYPOINT ["tia"]
