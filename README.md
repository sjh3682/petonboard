# 펫온보드 (Pet Onboard)

**반려견과 함께 달리는 동안, 차가 알아서 반려견을 살피는 Pleos Connect 앱**

뒷좌석에 반려견이 타면 창문 잠금·온도·조명을 한 번에 맞추고, 주행 중에는 짖음·낑낑거림과 차의 움직임을 함께 보며 운전자에게 음성으로 알려 줍니다. 쉬어야 할 때는 반려견 놀이터가 있는 휴게소로 길을 안내하고, 내릴 때는 리드줄을 채울 때까지 문을 잠가 둡니다. 주행 기록이 쌓일수록 우리 개에 맞게 휴식 간격이 바뀝니다.

- 대회: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect)
- APK: [`apk/서지혜_펫온보드_release.apk`](apk/)

---

## 주요 기능

| 기능 | 하는 일 | 쓰는 차량 기능 / SDK |
|---|---|---|
| F1 탑승 감지 | 정차 중 뒷좌석에 누가 타면 "뒷좌석에 누가 탔어요. 콩이면 '맞아'라고 말해 주세요"를 음성으로 물음 (착석 센서가 있는 차) | 좌석 탑승, 기어 · Vehicle, TTS, STT |
| F2 원터치 세팅 | 창문 잠금, 반려견 온도(단두종은 21°C 이하 권장), 풍량, 은은한 조명. 끝내면 원래대로 | 창문 잠금, 공조, 실내 조명 · Vehicle |
| F3 바람 쐬기 | 10~40km/h가 이어지면 "창문 조금 열까요?" → 머리가 나가지 않는 높이까지만 열고, 50km/h·와이퍼에 자동 닫힘 | 창문 위치, 속도, 와이퍼 · Vehicle, TTS, STT |
| F4 소리 감지 | 차 마이크로 짖음·낑낑거림 감지(차 안에서 처리, 녹음 저장 안 함). 급커브·급제동 직후면 부드러운 운전 안내, 잦으면 휴식 앞당김 | 마이크, 핸들·브레이크 · YAMNet(TFLite) |
| F5 휴식 | 휴식 시간이 되거나 불안 소리가 잦으면 가까운 반려견 놀이터 휴게소 추천 → 목적지가 있으면 경유지로 추가 | 위치, 내비 · FusedLocation, NaviHelper |
| F6 하차 안전 | 주행 후 P단이면 문을 잠가 두고 "리드줄 채웠어요"를 눌러야 열림 | 기어, 문 잠금 · Vehicle |
| F7 리포트·기록 | 주행마다 기록, 지난 평균과 비교, 불안 소리가 시작되는 시점으로 휴식 간격 자동 조정 | — |
| 안전 | 출발(P→D) 때 반려견 안전벨트 확인 음성, 벨트 센서가 있으면 풀림 경고 | 기어, 안전벨트 · Vehicle, TTS |

운전 중 화면을 누르지 않도록 모든 질문은 음성으로 묻고 "좋아 / 괜찮아"처럼 짧게 답합니다(화면 버튼도 가능).

## 실행 방법

### APK로 바로 실행
1. Pleos Connect Emulator(AVD `Pleos Connect v2.1.0`, API 34)를 **Cold Boot**로 켭니다.
2. `adb install -r apk/서지혜_펫온보드_release.apk`
3. 앱 목록에서 **펫온보드** 실행 → 속도·마이크·위치 권한 허용 → 반려견 프로필 저장

> 이 APK는 `debuggable`로 빌드했습니다. Pleos Connect 에뮬레이터는 앱마켓 심사를 거치지 않은 앱의 화면을 주행 중(D단·주행 속도)에 "주행 중에는 사용할 수 없습니다"로 가리기 때문에, 심사 중에도 주행 화면을 확인할 수 있게 한 설정입니다. 앱마켓에 배포할 때는 `isDebuggable = false`로 되돌립니다.

### 소스로 실행
1. Android Studio(2026.2 이상)에서 이 폴더를 엽니다. Gradle JDK는 **21**을 씁니다.
2. 기기 선택에서 `Pleos Connect v2.1.0`을 고르고 ▶ 실행
3. Pleos SDK는 `settings.gradle.kts`의 Pleos Playground Maven 저장소에서 받습니다.

## 테스트 환경 및 필요한 설정

- Android Studio 2026.2.1, AGP 8.8.2, Kotlin 2.0.0, Gradle 8.10.2, compileSdk 35, minSdk 28
- Pleos SDK: Vehicle 2.3.2, TextToSpeech 3.3.1, SpeechToText 3.3.1, NaviHelper 3.0.5, FusedLocation 1.1.3
- TensorFlow Lite 2.16.1 + YAMNet 모델(`app/src/main/assets/yamnet.tflite`)

**에뮬레이터 설정**
1. 차량 값 조작(Virtual Console): `adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/
   - 기어: `PUT /vehicle/gear?gear=Drive|Park`
   - 속도·브레이크·핸들: `PUT /vehicle/controls?speed=30&accelerator=30&brake=0&steering=0`
   - 뒷좌석 탑승: `PUT /vehicle/seat?area=rear_right&kind=occupied&value=true`
2. 마이크(음성 답·소리 감지): 에뮬레이터 ⋮ → Extended Controls → Microphone → **Enable Host Microphone Access**
3. 위치(휴게소 추천): Extended Controls → Location → `37.2490, 127.2050`(영동고속도로 마성 근처) → Set Location
4. 앱 왼쪽 위 **펫온보드** 글자를 누르면 실시간 차량 값과 테스트 버튼(탑승·바람 제안·휴식 알림·소리·샘플 소리·급제동·기록 지우기·벨트 풀림)이 나옵니다. "샘플 소리"는 녹음된 개 소리를 마이크 대신 감지 모델에 넣어 실제와 같은 과정으로 판단해요.

**시연 순서 예**
1. 홈 → 펫 모드 시작 (창문 잠금·온도·조명 적용, 음성 안내)
2. 기어 D → "출발할게요. 콩이 안전벨트를 채웠는지 확인해 주세요"
3. 속도 25km/h 10초 유지 → 바람 쐬기 제안 → "좋아" → 뒷창문 일부 열림 → 속도 60km/h → 자동 닫힘
4. 테스트 줄의 "샘플 소리"(녹음된 개 소리를 마이크 대신 입력) 또는 실제 소리 → "콩이가 짖었어요", 여러 번이면 휴식 앞당김 → "좋아" → 내비에 휴게소 경유지 추가
5. 기어 P → 문 잠김 → "리드줄 채웠어요, 문 열기" → 펫 모드 끝내기 → 리포트·기록

## 실제 차에서의 동작 원칙
- 차가 지원하지 않는 값(뒷좌석 착석, 안전벨트 등)은 시작할 때 확인해서, 없으면 해당 자동 기능을 끄고 수동 시작·음성 확인으로 대신합니다.
- 소리는 저장하거나 밖으로 보내지 않고, 0.5초마다 점수만 계산합니다. 음성 답을 듣는 동안에는 소리 감지를 멈춥니다.
- 원인을 단정하지 않고 "급제동 30초 뒤 낑낑거림"처럼 함께 일어난 일만 기록합니다.

## 근거
- 차 이동만으로 반려견 코르티솔·심박 상승, 낑낑거림 증가 (Hunt et al. 2023; Flint et al. 2024)
- 반복해도 익숙해지지 않고, 이동이 길수록 스트레스 증가 (Herbel et al. 2020)
- 스트레스 반응은 개마다 크게 다름 (Flint et al. 2025) → 기록 기반 개인화
- 단두종은 21~22°C에서도 과열 가능 (Carter et al. 2020; Hall et al. 2020)
- 반려동물을 안고 운전 금지 (도로교통법 제39조 제5항)

## 구조
```
app/src/main/java/ai/petonboard/app/
├─ vehicle/VehicleGateway.kt   차량 SDK 연결 (좌석, 기어, 핸들, 브레이크, 와이퍼, 창문, 문, 공조, 조명, 속도)
├─ logic/PetModeViewModel.kt   펫 모드 흐름 전체
├─ logic/TripStore.kt          주행 기록 저장, 패턴 분석, 휴식 간격 자동 조정
├─ logic/ProfileStore.kt       반려견 프로필
├─ sound/BarkDetector.kt       F4 짖음·낑낑거림 감지 (YAMNet)
├─ voice/Voice.kt              음성 안내(TTS)·음성 답(STT)
├─ navi/RestStops.kt           반려견 놀이터 휴게소 목록
├─ navi/RestNavigator.kt       위치, 내비 경유지·목적지 안내
└─ ui/                         화면 (홈, 프로필, 탑승, 주행, 하차, 리포트, 기록)
```

## 사용한 외부 자료와 라이선스
- YAMNet 모델: Google, Apache License 2.0 ([tensorflow/models](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet)). TFLite 변환본은 [larsyde/tflite-models-audioset-yamnet](https://github.com/larsyde/tflite-models-audioset-yamnet)에서 받음
- 반려견 놀이터 휴게소: 한국도로공사 자료 인용 기사(2025.6 기준), 좌표는 OpenStreetMap(ODbL)
- 테스트용 개 소리(`app/src/main/assets/test_dog.wav`): ESC-50 데이터셋(Piczak 2015, CC BY-NC 3.0, 원 출처 Freesound)의 dog 클립 1-30226-A-0, 1-97392-A-0을 16kHz로 변환해 이어 붙임. 에뮬레이터에서 마이크 대신 감지 과정을 확인하는 테스트 입력으로만 사용
