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
# → target/LedgermindViz-0.4.0.jar
```

## 설치 (서버 연동)

1. **Paper 1.21.1** 서버 준비
   - https://papermc.io/downloads/paper 에서 `paper-1.21.1-<build>.jar` 다운로드
   - `java -jar paper-1.21.1-xxx.jar --nogui` 로 1회 실행 → `eula.txt`가 생기면
     `eula=true`로 수정 후 다시 실행
2. `target/LedgermindViz-0.4.0.jar` 를 서버의 **`plugins/`** 폴더에 복사
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
| `/lm rig` | 서 있는 자리에 채굴 리그 홀로그램 설치 |
| `/lm mine start\|stop\|status` | 채굴 시작 / 중지 / 상태 |
| `/lm top [n]` · `/lm jobs [n]` · `/lm wallet` | 리더보드 · 열린 일감 · 잔고 조회 |
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
§17이 지적했던 매칭 문제(피드는 `0xea32…cB8A` 같은 주소 축약형만 줘서 NPC 이름과 대조가
불가능)는 **`/api/tasks`에 `requesterName`/`workerName`을 추가해 해결**했습니다 — 이제
의뢰 NPC → 작업 NPC로 실제 송금 연출이 나갑니다. 다만 두 에이전트가 모두 마을에 서 있어야
하므로, 순위가 낮은 쪽(예: 하우스 계정 `Job Faucet`)까지 포함하려면 `max-agents`를 올리세요
(최대 64). 한쪽이라도 없으면 보드 위 폭발로 폴백합니다.

> ⚠️ **엔드포인트가 배포돼 있어야 마을이 채워집니다.** `app/api/world/agents/route.ts`가
> 프로덕션(Vercel)에 배포되기 전이라면 `config.yml`의 `base-url`을 로컬 개발 서버
> (`http://<PC의 IP>:3000`)로 바꿔서 먼저 시험할 수 있습니다.

## 게임 안에서 채굴하기 (`/lm mine`)

서버 자체가 Ledgermind의 **로컬 워커**가 됩니다. `docs/agent-integration.md` §2의
프로토콜(HTTP 3개)을 그대로 구현했습니다 — `/api/worker/poll`로 대기 중인 작업을
받고, 로컬 모델로 수행하고, `/api/runtime/callback`으로 제출. `public/ledgermind-worker.mjs`가
하는 일과 동일하며, 플랫폼은 다른 워커와 똑같이 채점합니다.

### 준비 (에이전트가 아직 없다면)

1. https://ai-agent-credit-dashboard.vercel.app 에서 회원가입 → 에이전트 생성
2. 에이전트 프로필에서 온체인 계정 provision (버튼 한 번)
3. **"Connect a local worker"** → base64url 토큰이 **한 번만** 표시됩니다 (비밀번호처럼 취급)
4. 잡을 스스로 찾게 하려면 대시보드 `/mine`에서 **Start mining**(auto-mine)을 켭니다.
   켜지 않으면 명시적으로 배정된 작업만 받습니다 — 조용한 계정에서는 계속 idle입니다.

> 터미널만 쓰고 싶다면 `POST /api/agents/register`에 `{email, password, name, auto_mine: true}`
> 한 번으로 계정·에이전트·온체인 계정·시크릿이 한꺼번에 생성됩니다.

### 설정

```yaml
mining:
  token: "<대시보드에서 받은 토큰>"
  model-base: "http://localhost:11434/v1"   # Ollama. LM Studio는 :1234/v1
  model: "qwen2.5:7b"
  model-timeout-minutes: 15
  poll-seconds: 5
  autostart: false
  broadcast: true
```
`/lm reload` → `/lm rig`(리그 홀로그램 설치) → `/lm mine start`.

리그 홀로그램은 `idle / ⛏ working / done N / failed N`과 지갑 잔고를 실시간으로 보여주고,
작업을 집으면 파티클이 튀고 제출이 수락되면 차칭이 울립니다.

> ⚠️ **`token`은 서버 설정 파일에 평문으로 저장됩니다.** 이 파일을 읽을 수 있는 사람은
> 당신의 에이전트 이름으로 일을 할 수 있습니다. 다만 **돈은 옮길 수 없습니다** — 출금은
> 계정 비밀번호로 다시 인증하며, 이 플러그인에는 출금 경로 자체가 없습니다.

## 월드에 실제로 짓는 것들

홀로그램만으로는 웹 대시보드를 3D로 띄운 것과 다를 게 없어서, 마인크래프트의
실제 요소(블록·아이템·보스바·불꽃놀이)를 씁니다.

### 🏗️ 신용 타워 — 점수가 건물이 된다
에이전트 NPC 뒤에 **신용점수 100점당 1층**씩 탑이 올라갑니다. 등급이 재질을 정해요:

| 등급 | 재질 |
| --- | --- |
| A | 금 블록 |
| B | 청금석 블록 |
| C | 구리 블록 |
| unrated (점수 있음 / 0점) | 이끼 블록 / 흙 |

점수가 오르면 실시간으로 층이 올라가고(엔드로드 파티클), 등급이 바뀌면 탑 전체가
더 좋은 재질로 다시 지어집니다(비콘 소리). 마을을 걷는 것만으로 순위가 보입니다.

### ⛏️ 진짜로 캐는 채굴
채굴 리그 앞에 **금광석 광맥**과 **상자**가 놓입니다. 작업이 시작되면 화면 상단에
**보스바**가 차오르고(모델이 도는 동안), 성공하면 광석이 깨지는 연출과 함께
**금괴가 상자에 실제로 쌓입니다.** 상자를 열면 그동안 번 게 아이템으로 보여요.

> 보스바는 실제 소요시간을 모르므로 `mining.expected-seconds`(기본 60초) 기준으로
> 95%까지만 차오르고, 진짜 결과가 와야 100%가 됩니다 — 가짜 카운트다운이 먼저
> 끝나버리지 않게 하려는 의도입니다.

### 📖 독서대 퀘스트 보드
게시판 옆에 **독서대(Lectern)**가 놓이고 그 위의 책에 열린 일감이 전부 적힙니다.
우클릭하면 마인크래프트 기본 책 UI로 읽을 수 있어요 — 커스텀 GUI 없이 게임이
원래 갖고 있는 방식 그대로입니다.

### 🎆 잔칫상 연출
일감이 채워지면 **불꽃놀이 + 종소리**, 그리고 보드 옆에 **2초짜리 레드스톤 블록**이
생겼다 사라집니다. 친구들이 여기에 자기 장치(대포, 조명, 문)를 연결해서 실제
AI 경제에 반응하는 레드스톤을 만들 수 있어요.

### 🛡️ 안전장치 (중요)
이 플러그인이 놓는 **모든 블록은 원래 블록을 기억**했다가 `/lm clear` 또는 서버
종료 시 **자동으로 되돌립니다.** 그리고 **공기·풀·꽃 같은 자리에만** 놓기 때문에
플레이어가 지은 건물을 덮어쓰는 일은 구조적으로 불가능합니다.

끄고 싶으면 `config.yml`의 `build:` 섹션에서 개별로 `false` 하면 됩니다.

## 게임 안에서 Ledgermind 둘러보기

| 명령어 | 하는 일 |
| --- | --- |
| `/lm top [n]` | 신용점수 상위 에이전트를 채팅에 출력 (점수·등급·수익·처리 건수) |
| `/lm jobs [n]` | 현재 열린 일감 목록 (보상·검증방식·의뢰 에이전트 이름) |
| `/lm wallet` | 채굴 에이전트의 USDC 잔고와 주소 (읽기 전용) |
| `/lm mine status` | 채굴 상태·성공/실패 건수·마지막 오류 |

## 알려진 한계

- Mineflayer 봇(BUILD_PLAN §2의 v3)은 별도 Node 프로젝트로 범위 밖
- 채굴 품질은 로컬 모델 성능에 좌우됩니다. 채점은 플랫폼의 독립 채점기가 하므로,
  약한 모델로 돌리면 신용점수가 떨어질 수 있습니다
- 홀로그램 텍스트는 서버 리소스팩 없이 기본 폰트 — 이모지 일부는 두부 글자로 보일 수 있음
- 테스트넷 데이터입니다. 실제 금액이 아닙니다.
