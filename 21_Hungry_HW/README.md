# pillBox 로컬 통신 테스트

1. 노트북과 ESP32를 같은 Wi-Fi에 연결합니다. 현재 ESP32 설정은 Bod 핫스팟입니다.
2. PowerShell에서 서버를 실행합니다.

```powershell
cd "$env:USERPROFILE\Desktop\pillBox"
npm.cmd install
npm.cmd start
```

서버를 실행한 창은 열어 두세요. 종료는 Ctrl+C입니다.

3. 노트북 브라우저에서 http://localhost:3000/api/schedule/pillbox-001 을 엽니다. deviceId, timezone, schedule 배열이 보이면 서버가 정상입니다.
4. 다른 PowerShell 창에서 `ipconfig`를 실행하고 ESP32와 같은 Wi-Fi 어댑터의 IPv4 주소를 확인합니다. `pillBox.py`의 `SERVER_URL`을 예를 들어 `http://172.20.10.2:3000`으로 변경합니다. 현재 들어 있는 `192.168.0.100`은 예시이므로 반드시 바꾸세요. localhost나 ESP32 자체 IP를 넣지 마세요.
5. Thonny에서 ESP32에 연결해 `pillBox.py`를 실행합니다. 자동 실행하려면 ESP32에 `main.py`로 저장하세요. 노트북 파일 수정만으로 ESP32 파일이 바뀌지는 않습니다.
6. 콘솔에 Wi-Fi 연결 성공, ESP32 IP, 스케줄 요청 주소, 복용 스케줄 JSON이 순서대로 출력되는지 확인합니다. 서버 창에도 요청이 표시됩니다.

Wi-Fi LED(GPIO18)는 연결 중 깜빡임, 성공 시 켜짐, 실패 시 꺼짐을 유지합니다. 서버 요청 실패는 Wi-Fi 연결 실패와 별개이므로 LED를 끄지 않습니다. 연결 성공 후 한 번 요청하며, 다시 요청하려면 코드를 다시 실행하세요. 버튼 GPIO22/23/5와 복약 LED GPIO4/16/17은 제어하지 않습니다. 서버의 시간과 핀 데이터는 테스트용 예시입니다.

## 연결이 안 될 때

- requests 모듈이 없으면 Thonny의 도구 → 패키지 관리에서 ESP32에 `requests`를 설치합니다. 기존 `urequests`도 지원합니다.
- Windows 방화벽 안내가 뜨면 사용 중인 신뢰하는 로컬 네트워크에서 Node.js 연결을 허용합니다.
- 노트북 브라우저에서도 `http://노트북IPv4:3000/api/schedule/pillbox-001`이 열리는지 확인합니다.
- 핫스팟에서 기기 간 통신을 차단하면 기기 간 통신이 가능한 같은 공유기 Wi-Fi로 옮깁니다.
- 404는 DEVICE_ID가 등록되지 않았다는 의미입니다. 기본값은 `pillbox-001`입니다.
- 시간 초과라면 주소, 같은 네트워크 여부, 방화벽, 서버 실행 상태를 확인합니다.

서버는 모든 네트워크 인터페이스에서 요청을 받습니다. 로컬 테스트용이므로 공유기 포트 포워딩은 설정하지 마세요.
