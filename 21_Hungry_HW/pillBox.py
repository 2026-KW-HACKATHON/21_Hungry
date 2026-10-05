from machine import Pin
import network
import ntptime
import time
import urequests


# ==================================================
# 설정
# ==================================================

WIFI_SSID = "Bod"
WIFI_PASSWORD = "와이파이비밀번호"

# 로컬 테스트라면:
# SERVER_URL = "http://192.168.x.x:3000"

# 나중에 실제 서버 배포하면:
# SERVER_URL = "https://your-server.com"

SERVER_URL = "http://서버주소:3000"

DEVICE_ID = "pillbox-001"

# 서버 스케줄 갱신 주기
SCHEDULE_REFRESH_SECONDS = 600   # 10분

# Wi-Fi 연결 재시도 간격
WIFI_RETRY_SECONDS = 5

# 메인 루프 간격
LOOP_DELAY_MS = 100


# ==================================================
# 핀
# ==================================================

buttons = {
    "morning": Pin(22, Pin.IN, Pin.PULL_UP),
    "lunch": Pin(23, Pin.IN, Pin.PULL_UP),
    "dinner": Pin(5, Pin.IN, Pin.PULL_UP)
}

leds = {
    "morning": Pin(4, Pin.OUT),
    "lunch": Pin(16, Pin.OUT),
    "dinner": Pin(17, Pin.OUT)
}

# 어썸보드 8번
wifi_led = Pin(18, Pin.OUT)


# 모든 LED OFF
for led in leds.values():
    led.value(0)

wifi_led.value(0)


# ==================================================
# Wi-Fi
# ==================================================

wlan = network.WLAN(network.STA_IF)
wlan.active(True)


def connect_wifi():
    if wlan.isconnected():
        wifi_led.value(1)
        return True

    print("Wi-Fi 연결 시도 중...")

    try:
        wlan.disconnect()
    except:
        pass

    time.sleep_ms(500)

    try:
        wlan.connect(WIFI_SSID, WIFI_PASSWORD)
    except Exception as e:
        print("Wi-Fi 연결 시작 오류:", e)
        wifi_led.value(0)
        return False

    # 최대 약 20초
    for _ in range(40):

        if wlan.isconnected():
            wifi_led.value(1)

            print("Wi-Fi 연결 성공")
            print("ESP32 IP:", wlan.ifconfig()[0])

            return True

        # 연결 중 LED 깜빡임
        wifi_led.value(1)
        time.sleep_ms(250)

        wifi_led.value(0)
        time.sleep_ms(250)

    wifi_led.value(0)

    print("Wi-Fi 연결 실패")

    return False


# ==================================================
# 시간
# ==================================================

def sync_time():
    if not wlan.isconnected():
        return False

    try:
        ntptime.settime()

        print("NTP 시간 동기화 성공")

        return True

    except Exception as e:

        print("NTP 시간 동기화 실패:", e)

        return False


def get_korea_time():

    # NTP는 UTC 기준
    korea_seconds = time.time() + (9 * 60 * 60)

    return time.localtime(korea_seconds)


def current_minutes():

    now = get_korea_time()

    hour = now[3]
    minute = now[4]

    return hour * 60 + minute


def time_to_minutes(value):

    hour, minute = value.split(":")

    return int(hour) * 60 + int(minute)


# ==================================================
# 기본 스케줄
# 서버 접속 실패 시 사용할 값
# ==================================================

schedule = {
    "morning": {
        "time": "08:00",
        "graceMinutes": 60
    },

    "lunch": {
        "time": "13:00",
        "graceMinutes": 60
    },

    "dinner": {
        "time": "19:00",
        "graceMinutes": 60
    }
}


# ==================================================
# 상태
# ==================================================

states = {
    "morning": "WAITING",
    "lunch": "WAITING",
    "dinner": "WAITING"
}


# 미복용 서버 전송 성공 여부
missed_sent = {
    "morning": False,
    "lunch": False,
    "dinner": False
}


# 버튼 이전 상태
previous_button = {
    "morning": 1,
    "lunch": 1,
    "dinner": 1
}


# ==================================================
# 서버에서 스케줄 받기
# ==================================================

def fetch_schedule():

    global schedule

    if not wlan.isconnected():
        print("Wi-Fi 없음 - 스케줄 요청 불가")
        return False

    url = (
        SERVER_URL
        + "/api/schedule/"
        + DEVICE_ID
    )

    print("스케줄 요청:", url)

    response = None

    try:

        response = urequests.get(url)

        print(
            "스케줄 HTTP:",
            response.status_code
        )

        if response.status_code != 200:
            return False

        data = response.json()

        # 필요한 값만 가져오기
        schedule["morning"]["time"] = \
            data["morning"]["time"]

        schedule["morning"]["graceMinutes"] = \
            data["morning"]["graceMinutes"]


        schedule["lunch"]["time"] = \
            data["lunch"]["time"]

        schedule["lunch"]["graceMinutes"] = \
            data["lunch"]["graceMinutes"]


        schedule["dinner"]["time"] = \
            data["dinner"]["time"]

        schedule["dinner"]["graceMinutes"] = \
            data["dinner"]["graceMinutes"]


        print("스케줄 업데이트 완료")

        print(
            "아침:",
            schedule["morning"]["time"]
        )

        print(
            "점심:",
            schedule["lunch"]["time"]
        )

        print(
            "저녁:",
            schedule["dinner"]["time"]
        )

        return True


    except Exception as e:

        print(
            "스케줄 요청 실패:",
            e
        )

        return False


    finally:

        if response:

            try:
                response.close()
            except:
                pass


# ==================================================
# 미복용 서버 전송
# ==================================================

def send_missed(period):

    if not wlan.isconnected():
        return False

    url = (
        SERVER_URL
        + "/api/medication/missed"
    )

    data = {
        "deviceId": DEVICE_ID,
        "period": period,
        "status": "missed",
        "scheduledTime": schedule[period]["time"]
    }

    response = None

    try:

        print(
            period,
            "미복용 서버 전송"
        )

        response = urequests.post(
            url,
            json=data
        )

        print(
            "HTTP:",
            response.status_code
        )

        if (
            response.status_code >= 200
            and
            response.status_code < 300
        ):

            print(
                period,
                "미복용 전송 성공"
            )

            return True

        return False


    except Exception as e:

        print(
            period,
            "미복용 전송 실패:",
            e
        )

        return False


    finally:

        if response:

            try:
                response.close()
            except:
                pass


# ==================================================
# 하루 초기화
# ==================================================

def reset_day():

    print("날짜 변경 - 상태 초기화")

    for period in states:

        states[period] = "WAITING"

        missed_sent[period] = False

        previous_button[period] = 1

        leds[period].value(0)


# ==================================================
# 버튼 눌림 감지
# ==================================================

def button_pressed(period):

    current = buttons[period].value()

    # 1 → 0 순간만 버튼 입력으로 판단
    pressed = (
        previous_button[period] == 1
        and
        current == 0
    )

    previous_button[period] = current

    return pressed


# ==================================================
# 복용 상태 확인
# ==================================================

def check_period(period, now_minutes):

    start = time_to_minutes(
        schedule[period]["time"]
    )

    grace = int(
        schedule[period]["graceMinutes"]
    )

    end = start + grace


    # ------------------------------
    # 복용 시작
    # ------------------------------

    if (
        states[period] == "WAITING"
        and
        now_minutes >= start
        and
        now_minutes < end
    ):

        states[period] = "ACTIVE"

        leds[period].value(1)

        print(
            period,
            "복용시간 시작"
        )


    # ------------------------------
    # 복용 버튼
    # ------------------------------

    if states[period] == "ACTIVE":

        if button_pressed(period):

            states[period] = "TAKEN"

            leds[period].value(0)

            print(
                period,
                "복용 완료"
            )

            # 정상 복용은 서버 전송하지 않음


    # ------------------------------
    # 미복용
    # ------------------------------

    if (
        states[period] == "ACTIVE"
        and
        now_minutes >= end
    ):

        states[period] = "MISSED"

        leds[period].value(0)

        print(
            period,
            "미복용 발생"
        )


    # ------------------------------
    # 미복용 서버 전송
    # 실패했으면 다음 루프에서 재시도
    # ------------------------------

    if (
        states[period] == "MISSED"
        and
        not missed_sent[period]
    ):

        if send_missed(period):

            missed_sent[period] = True


# ==================================================
# 시작
# ==================================================

print("==========================")
print("PillBox 시작")
print("Device ID:", DEVICE_ID)
print("==========================")


while not connect_wifi():

    print(
        WIFI_RETRY_SECONDS,
        "초 후 Wi-Fi 재시도"
    )

    time.sleep(
        WIFI_RETRY_SECONDS
    )


sync_time()

fetch_schedule()


now = get_korea_time()

current_date = (
    now[0],
    now[1],
    now[2]
)


last_schedule_refresh = time.time()


# ==================================================
# 메인 루프
# ==================================================

while True:

    # ------------------------------
    # Wi-Fi 상태 확인
    # ------------------------------

    if not wlan.isconnected():

        wifi_led.value(0)

        print("Wi-Fi 연결 끊김")

        connect_wifi()

    else:

        wifi_led.value(1)


    # ------------------------------
    # 현재 날짜 확인
    # ------------------------------

    now = get_korea_time()

    new_date = (
        now[0],
        now[1],
        now[2]
    )


    if new_date != current_date:

        current_date = new_date

        reset_day()

        sync_time()

        fetch_schedule()


    # ------------------------------
    # 스케줄 갱신
    # ------------------------------

    if (
        time.time()
        - last_schedule_refresh
        >= SCHEDULE_REFRESH_SECONDS
    ):

        fetch_schedule()

        last_schedule_refresh = time.time()


    # ------------------------------
    # 복용 상태 확인
    # ------------------------------

    minutes = current_minutes()

    check_period(
        "morning",
        minutes
    )

    check_period(
        "lunch",
        minutes
    )

    check_period(
        "dinner",
        minutes
    )


    time.sleep_ms(
        LOOP_DELAY_MS
    )