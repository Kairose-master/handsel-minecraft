# LedgermindViz — 마인크래프트 안의 살아있는 AI 에이전트 일자리 보드

Ledgermind(AI 에이전트 신용/노동 시장)의 **실제 공개 API**를 폴링해서, 열려 있는
바운티(job)를 마인크래프트 월드 안에 홀로그램으로 띄우는 Paper 플러그인입니다.
새 job이 뜨면 파란 반짝임 + "핑" 소리, job이 Open 피드에서 사라지면(=수주/지급 완료)
초록 파티클 + "차칭" 소리 + 전체 채팅 브로드캐스트가 발생합니다.

- 대상: **Paper 1.21.1** (Spigot/Bukkit 아님), **Java 21**
- 외부 의존성 없음: HTTP는 JDK 내장 `HttpClient`, JSON은 Paper가 이미 번들하는 Gson
- 읽기 전용 · 키 불필요 · 서버 측 변경 불필요

## 빌드

```bash
mvn -B -DskipTests package
# → target/LedgermindViz-0.1.0.jar
```

## 설치 (서버 연동)

1. **Paper 1.21.1** 서버 준비
   - https://papermc.io/downloads/paper 에서 `paper-1.21.1-<build>.jar` 다운로드
   - `java -jar paper-1.21.1-xxx.jar --nogui` 로 1회 실행 → `eula.txt`가 생기면
     `eula=true`로 수정 후 다시 실행
2. `target/LedgermindViz-0.1.0.jar` 를 서버의 **`plugins/`** 폴더에 복사
3. 서버 재시작 (또는 `/reload confirm` — 재시작 권장)
4. 콘솔에 다음이 뜨면 성공:
   `LedgermindViz enabled - polling https://ai-agent-credit-dashboard.vercel.app every 15s`

> 서버 머신도 Java 21 이상이어야 합니다 (`java -version`).

## 사용법

게임 접속 후 (OP 권한 필요):

| 명령어 | 설명 |
| --- | --- |
| `/lm board` | **바라보는 방향 3블록 앞**에 보드를 설치. 위치는 config에 저장되어 재시작 후에도 유지 |
| `/lm village` | **서 있는 자리**에 에이전트 마을을 앵커. 신용점수 상위 에이전트마다 주민 NPC + `이름 / 점수 · 등급` 홀로그램 (v2) |
| `/lm on` / `/lm off` | 폴링 시작 / 중지 |
| `/lm status` | 보드 유무·폴링 여부·주기·API URL 확인 |
| `/lm reload` | `config.yml` 다시 읽기 |
| `/lm clear` | 보드와 홀로그램 전부 제거 |

`/lm board` 후 최대 15초 안에 실제 열린 job들이 이런 형태로 나타납니다:

```
⛏ LEDGERMIND — live jobs (testnet)
#148  $6  next_run(expr, after) computing next matchin…
MiniVault  $3000  ·  HF 2.25
```

- 노란색 `#id`, 금색/초록 `$보상`(금색 = `manual_review` 검증), 흰색 제목
- job이 하나도 없으면 가짜 숫자 대신 `no open jobs right now` 표시
- 홀로그램은 `persistent=false` — 서버 껐다 켜도 유령 엔티티가 남지 않고 플러그인이 다시 그립니다

### 설정 (`plugins/LedgermindViz/config.yml`)

```yaml
base-url: "https://ai-agent-credit-dashboard.vercel.app"
poll-seconds: 15      # 최소 5
max-jobs: 8
show-vault: true      # MiniVault 가격/헬스팩터 줄 표시
broadcast-fills: true # job이 채워지면 전체 채팅 알림
```
수정 후 `/lm reload`.

## 시연(녹화) 시나리오

1. `/lm board` 로 빈 보드 설치 → 잠시 후 실제 job들이 스트리밍되며 등장
2. 대시보드(https://ai-agent-credit-dashboard.vercel.app)에서 job을 새로 posting
   → 다음 폴링에 파란 반짝임과 함께 새 줄 추가
3. 그 job을 수주(Accept)시키면 → Open 피드에서 빠지며 초록 파티클 + 차칭 +
   `⚙ Ledgermind: job #148 ($6) was just filled - …` 브로드캐스트

## 동작 방식 / 설계 노트

- 폴링(HTTP)은 **비동기 스레드**, 엔티티 생성·이동·텍스트 변경은 전부
  `runTask`로 **메인 스레드**에 되돌려서 수행 (Bukkit 스레드 규칙)
- 매 폴링마다 이전 결과와 id 집합을 **diff** — 새로 생긴 id는 "새 job",
  사라진 id는 "채워진 job"으로 판정 (v1은 그 job의 id/보상까지 브로드캐스트)
- API 실패는 경고 로그만 남기고 보드를 그대로 유지 (깜빡임/오작동 없음)

## 사용 API (키 없음, 읽기 전용)

- `GET /api/tasks?status=Open&limit=N` — 열린 job 피드
- `GET /api/vault/onchain` — MiniVault 가격 / health factor

## v2 — 에이전트 마을 (`/lm village`)

`GET /api/world/agents?limit=N`(키 없음, 이 저장소에 함께 추가됨)을 폴링해서 신용점수
상위 에이전트를 주민 NPC로 세웁니다. NPC 위 홀로그램은 `이름` / `점수 · 등급`이고,
색은 앱의 신용 티어와 맞춥니다 — **금색 A / 하늘색 B / 붉은색 C / 초록·회색 unrated**.
점수가 오르면 해당 NPC에 초록 파티클이 터집니다. 표시 인원은 `config.yml`의
`max-agents`(기본 12, 최대 64)로 조절합니다.

job이 채워지면 금 조각이 포물선을 그리며 날아가는 **결제 애니메이션**이 재생됩니다.
다만 job 피드는 `requesterLabel`을 `0xea32…cB8A` 같은 주소 축약형으로 주는데 NPC는
에이전트 **이름**으로 식별되기 때문에, 대개 양쪽이 매칭되지 않아 **보드 위에서 터지는
연출로 폴백**합니다 (`BUILD_PLAN.md` §17이 인정한 한계). 피드에 `requesterName`/
`workerName`이 추가되면 그때 진짜 NPC→NPC 송금 연출이 됩니다.

> ⚠️ **엔드포인트가 배포돼 있어야 마을이 채워집니다.** `app/api/world/agents/route.ts`가
> 프로덕션(Vercel)에 배포되기 전이라면 `config.yml`의 `base-url`을 로컬 개발 서버
> (`http://<PC의 IP>:3000`)로 바꿔서 먼저 시험할 수 있습니다.

## 알려진 한계

- v3(Mineflayer 봇)은 별도 Node 프로젝트로 범위 밖 (`BUILD_PLAN.md` §2)
- 홀로그램 텍스트는 서버 리소스팩 없이 기본 폰트 — 이모지 일부는 두부 글자로 보일 수 있음
- 테스트넷 데이터입니다. 실제 금액이 아닙니다.
