const express = require('express');
const os = require('node:os');

const app = express();
const port = Number(process.env.PORT || 3000);

// 로컬 통신 테스트용 샘플 데이터입니다. 서버 재시작 시 그대로 유지됩니다.
const schedules = {
  'pillbox-001': {
    deviceId: 'pillbox-001',
    timezone: 'Asia/Seoul',
    schedule: [
      { time: '08:00', slot: 1, label: '아침', buttonGpio: 22, ledGpio: 4 },
      { time: '13:00', slot: 2, label: '점심', buttonGpio: 23, ledGpio: 16 },
      { time: '20:00', slot: 3, label: '저녁', buttonGpio: 5, ledGpio: 17 },
    ],
  },
};

app.get('/api/schedule/:deviceId', (req, res) => {
  const schedule = Object.hasOwn(schedules, req.params.deviceId)
    ? schedules[req.params.deviceId] : undefined;
  console.log(`스케줄 요청: ${req.params.deviceId}`);
  if (!schedule) {
    return res.status(404).json({ error: '등록되지 않은 deviceId입니다.' });
  }
  res.json(schedule);
});

const server = app.listen(port, '0.0.0.0', () => {
  console.log(`노트북 테스트: http://localhost:${port}/api/schedule/pillbox-001`);
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family === 'IPv4' && !address.internal) {
        console.log(`ESP32 SERVER_URL 후보: http://${address.address}:${port}`);
      }
    }
  }
  console.log('ESP32와 같은 Wi-Fi의 IPv4 주소를 사용하세요. 종료: Ctrl+C');
});

server.on('error', (error) => {
  console.error('서버 실행 실패:', error.message);
  process.exitCode = 1;
});
