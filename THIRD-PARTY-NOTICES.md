# Third-party notices

`test-impact-analysis`(TIA) 자체는 **MIT**(루트 `LICENSE`)입니다. 배포 산출물(단일 실행
fat-jar `tia.jar`, Docker 이미지 `ghcr.io/<owner>/tia`, GitHub Packages 아티팩트)에는 아래
서드파티 컴포넌트가 **번들**됩니다. 각 라이선스 전문은 `licenses/`에 동봉되어 있습니다.

| 컴포넌트 | 좌표 | 라이선스 | 소스 |
|---|---|---|---|
| Jackson (core·databind·annotations) | `com.fasterxml.jackson.core:*` | Apache-2.0 | https://github.com/FasterXML/jackson |
| picocli | `info.picocli:picocli` | Apache-2.0 | https://github.com/remkop/picocli |
| RoaringBitmap | `org.roaringbitmap:RoaringBitmap` | Apache-2.0 | https://github.com/RoaringBitmap/RoaringBitmap |
| SQLite JDBC | `org.xerial:sqlite-jdbc` | Apache-2.0 (+ 네이티브 SQLite: public domain; zentus 라이선스 동봉) | https://github.com/xerial/sqlite-jdbc |
| ASM | `org.ow2.asm:asm*` (JaCoCo 전이) | BSD-3-Clause | https://asm.ow2.io |
| **JaCoCo** (core·report) | `org.jacoco:org.jacoco.core`, `…report` | **EPL-2.0** | https://github.com/jacoco/jacoco |

## EPL-2.0 (JaCoCo) 고지

JaCoCo는 **Eclipse Public License v2.0**(약한, 파일 단위 copyleft)이며 TIA의 MIT 코드에
전염되지 않습니다(EPL은 해당 EPL 파일에만 적용). EPL-2.0 §3에 따라:

- 라이선스 전문: `licenses/EPL-2.0.txt`.
- **대응 소스 코드**: JaCoCo 소스는 https://github.com/jacoco/jacoco 및 Maven Central
  (`org.jacoco:org.jacoco.core:0.8.12` `-sources.jar`)에서 공개적으로 제공됩니다. TIA는 JaCoCo를
  수정 없이 그대로 번들합니다.

## 라이선스 전문 위치
- `licenses/Apache-2.0.txt` — Jackson·picocli·RoaringBitmap·SQLite JDBC
- `licenses/BSD-3-Clause-ASM.txt` — ASM
- `licenses/EPL-2.0.txt` — JaCoCo

> 빌드/CI가 외부에서 가져다 쓰는 커버리지 에이전트(teamscale-jacoco-agent: Apache-2.0 /
> parallel-per-test-coverage)는 TIA가 **번들하지 않으며**(사용자 제공, 설계 §5.3) 본 고지의
> 대상이 아닙니다.
