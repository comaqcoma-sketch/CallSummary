# 통화 요약 (Android)

휴대폰 **기본 전화 앱이 녹음한 통화**를 폰 안에서 텍스트로 바꾸고, 화자(A/B)를 구분하고, AI로 요약합니다.

- 통화를 직접 녹음하지 않습니다. 전화 앱의 통화 녹음 파일을 **읽기만** 하며 원본은 수정·삭제하지 않습니다.
- 저장소·전화·마이크 권한 없음. 사용자가 고른 녹음 폴더 하나만 읽기 권한으로 사용
- 모든 처리는 폰 안에서 (인터넷은 최초 1회 모델 다운로드에만 사용), 무료
- 요약: 한 줄 요약 · 주요 내용 · 약속·일정 · 할 일
- 새 통화 자동 정리 (앱을 열면 새 통화를 차례로 처리)

## 설치
휴대폰 브라우저에서:
`https://github.com/comaqcoma-sketch/CallSummary/releases/latest/download/call-summary.apk`

## 사용
1. 전화 앱에서 통화 녹음 켜기 (갤럭시: 전화 앱 → ⋮ → 설정 → 통화 녹음 → 통화 자동 녹음)
2. 앱에서 통화 녹음 폴더 선택 (갤럭시: 내장 저장공간 → Recordings → Call)
3. 통화를 눌러 "텍스트 변환 + 요약"

## 직접 빌드
[sherpa-onnx-1.13.7.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar)를 `app/libs/sherpa-onnx.aar`로 저장 후 Android Studio에서 실행.
