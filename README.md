# 낮잠 알람 (가칭)

주차한 차에서 잠깐 쉴 때 휴식 시간을 정하고, 시간이 되면 깨워 주고 차량 설정 복구를 돕는 앱입니다. Pleos Connect용으로 개발합니다.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작 · 시제품

**공조·시트는 모의 차량으로만 동작합니다.** 화면 상단에 항상 "차량 연동 시뮬레이션"을 표시하며, 실제 차량 제어 어댑터는 이 빌드에 없습니다. 경적·원격 시동은 구현하지 않았습니다. 이 앱은 기상이나 차 안 체류 안전을 보장하는 장치가 아니며, 앱이 켜져 있고 차량 시스템이 깨어 있을 때만 알람이 울립니다.

## 무엇이 실제로 동작하나

| 기능 | 상태 |
|---|---|
| 휴식 시간 설정(15·30·60분, 직접 조정 1~180분), 예상 알람 시각 | 실제 |
| 카운트다운 — 시작·목표 시각 기준(단조 시계), 화면을 다시 그려도 초기화 안 됨 | 실제 |
| 알람 소리(작게 시작해 점점 크게)·음성 안내, 기상 확인·5분 다시 알림 | 실제 |
| 앱이 꺼졌다 켜지면 남은 시간 복원, 시간이 지났으면 "알람이 울리지 않았어요"로 표시·기록 | 실제 |
| 재부팅·시각 변경 감지 시 일정 다시 확인 | 실제 |
| 음성 명령("30분", "한 시간 반", "일어났어", "5분 더") — 음성 시간은 다시 물어 확인 | 실제(Pleos STT/TTS) |
| 공조 온도·시트 자세 준비, 요청 상태(모의 적용됨/실패/응답 없음/차단됨) | 모의 차량 |
| 원래 설정 복구 — 앱이 바꾼 항목만, 사용자가 직접 바꾼 값은 덮어쓰지 않고 확인 | 규칙은 실제, 차량은 모의 |
| 휴대폰 "10분 전 준비" 예약 | 모의 이벤트 |
| 기록(계획 시간·실제 앱 세션 시간·종료 사유) — 수면 측정 아님 | 실제 |

자세한 범위는 [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md), 검증 결과는 [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md).

## 구조

```
core/            순수 Kotlin — NapEngine(타이머 상태 전이·재시작 복원), VehicleControl(제어 요청·복구 규칙), VoiceParser(음성 시간·명령), Texts
core/src/test/   계획서 검증 사례 N-T01~N-T09 단위 테스트(가짜 시계로 시간·재부팅·시각 변경 재현)
app/             Compose 화면, 저장(원자적 JSON), 알람 소리, Pleos/기기 음성, 모의 차량(MockCar), 시연 패널
```

## 실행

요구 사항: Android Studio(JDK 17 이상), Android SDK 36

```
./gradlew :core:test            # 단위 테스트
./gradlew :app:assembleRelease  # 서명 키(~/.android-keys/carvoicegame.properties)가 있으면 서명된 APK
```

테스트 환경: Pleos Connect v2.1.0 에뮬레이터(API 34, Automotive). 시연은 시연 패널의 "20초 타이머로 시작(시간 단축)"을 쓰며, 영상에도 시간 단축임을 표시합니다.

기록은 기기 안에만 저장되며 앱을 지우거나 데이터를 초기화하면 사라집니다.

## APK

릴리스 APK는 팀명·앱 이름이 정해지면 `apk/` 폴더에 공식 파일명으로 둡니다.

## 문서

| 파일 | 내용 |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | 개발 계획서 — 구현 범위(P0/P1), 사용자 흐름, 상태·데이터 모델, 검증 사례 |
| [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) | 기능별 구현 상태와 한계 |
| [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) | 검증 사례별 결과 |
| [`docs/COMMON.md`](docs/COMMON.md) | 세 앱 공통 개발·빌드·검증 원칙, 일정, release 절차 |
| [`docs/SUBMISSION.md`](docs/SUBMISSION.md) | 1차 제출·가산점 준비 체크리스트 |
