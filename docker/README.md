# D2 — Docker 이미지 + GitHub Action (CI 도입)

배포 설계 §4 Phase D2. **impact-only**(이미 만들어진 인덱스 + diff를 소비해 선별만; JDK17).
무거운 `convert`/`index`(SUT 실행·classfile 필요)는 빌드/플러그인/사전 CI 단계의 일이다.

## 이미지

```bash
./gradlew :tia-cli:shadowJar                       # tia.jar 생성(이미지가 COPY)
docker build -f docker/Dockerfile -t tia:local .
docker run --rm tia:local --version                # → tia <ver>
docker run --rm -v "$PWD:/work" -w /work tia:local \
  impact --db tia.db --commit <baseline-sha> --diff-file change.diff
```

엔트리포인트 래퍼 `/usr/local/bin/tia` 가 `java -jar /opt/tia/tia.jar "$@"` 를 실행한다.
태그(`v*`) 푸시 시 `.github/workflows/publish-image.yml` 가 `ghcr.io/<owner>/tia:<tag>`·`:latest`
로 빌드·푸시한다(로그인=`GITHUB_TOKEN`).

## GitHub Action (`action.yml`)

PR에서 변경 영향 테스트를 선별해 Job Summary에 표시하고 `selected` 출력으로 노출한다.

```yaml
permissions:
  pull-requests: write   # pr-comment: 'true' 사용 시 필요(코멘트 게시 API)

- uses: actions/checkout@v4
- id: tia
  uses: baekchangjoon/test-impact-analysis@v0.1.0   # 릴리스 태그로 핀(릴리스 전이면 @main 또는 SHA)
  with:
    db: tia.db                                       # baseline 인덱스(워크스페이스 상대경로)
    commit: ${{ github.event.pull_request.base.sha }}
    diff-file: change.diff                           # 생략 시 워킹트리 vs git-ref/commit
    # git-ref: <ref>                                 # diff 베이스 ref(기본 = commit)
    # strict: 'true'                                 # 베이스라인 없으면 run-all 대신 실패
    pr-comment: 'true'                               # (선택, 기본 'false') PR에 결과 코멘트 게시
    # github-token: ${{ secrets.GITHUB_TOKEN }}      # (선택, 기본 = github.token)
- name: run impacted (or all)
  run: |
    if [ "${{ steps.tia.outputs.run-all }}" = "true" ]; then
      ./gradlew test                                 # 베이스라인 부재 → 전체 실행(보수적, 누락 0)
    else
      echo '${{ steps.tia.outputs.selected }}' | ...  # 선별 테스트만 실행
    fi
```

**데이터 플로우 / DB 부재·커밋 불일치:** baseline DB 취득(아티팩트·캐시·릴리스) → diff →
`tia impact` → 선별. **DB 파일이 없거나** 인덱스에 해당 커밋의 베이스라인이 없으면(CLI가
`# tia:no-baseline` 마커 출력) action은 **`run-all=true`** 를 내보낸다 → 호출측은 **전체
실행**(보수적, **누락 위험 0**). 빈 `selected`(아무것도 안 돌림)와 명확히 구분된다. `strict:
'true'` 면 그 경우 CLI가 비0으로 실패해 스텝이 실패한다.

출력: `selected`(`<CONFIDENCE>\t<testId>` 줄), `run-all`(`true`/`false`).

**PR 코멘트(opt-in, `pr-comment: 'true'`).** 결과를 PR에 코멘트로도 게시한다(부가 기능 — 실패해도
빌드는 안 깨진다). 전제:
- 워크플로에 **`permissions: pull-requests: write`** 가 있어야 코멘트 게시 API가 성공한다.
- **포크에서 온 PR**은 GitHub이 기본 `GITHUB_TOKEN`을 read-only로 내려주므로, 위 permissions를
  줘도 게시가 실패할 수 있다 — 이 경우 스텝은 실패시키지 않고 `::warning`만 남기고 스킵한다.
- PR 이벤트가 아니거나(`push` 등) markdown 재생성이 실패해도 동일하게 경고 후 스킵한다(빌드
  실패로 전파되지 않음).
- `github-token` 입력으로 별도 토큰(예: PAT)을 넘기면 포크 PR에서도 게시할 수 있다.

## 검증 / 범위

- 로컬 검증됨: 이미지 빌드, `docker run … --version`, 컨테이너 내 `tia impact`(fixture DB →
  `DETERMINISTIC<TAB>T#m`), action의 impact 스텝 로직(선별/ DB-부재 분기).
- **실 릴리스 시 검증(설계 §7 E2E-3, O3):** ghcr push, 전용 샌드박스 레포의 샘플 PR 게시.
  PR-코멘트 자동검증은 초기 수동, 자동화는 후속.
