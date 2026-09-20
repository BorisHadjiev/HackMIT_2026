// GY-521 (MPU-6050) pitch tracker for Arduino UNO Q + App Lab
// Replace the ENTIRE sketch (Ctrl+A, paste).
//
// Only pitch is reported. Raw pitch on this mount falls from 70 deg (down)
// to 0 deg (raised). That is remapped to 0 (down) through 60 (raised),
// and the displayed value is capped at 100.
//
// IMU wiring: VCC-3.3V, GND-GND, SCL-SCL, SDA-SDA
// Right GY-521: AD0 to 3.3V (0x69). Left: AD0 open or GND (0x68).
//
// Bluetooth: UNO Q onboard BLE (Qualcomm/Linux BlueZ), not an HC-05.
// STM32 sketch <-> Linux via Arduino_RouterBridge. python/main.py advertises
// Nordic UART Service ("StrokeSense") to the phone:
//   service   6E400001-B5A3-F393-E0A9-E50E24DCCA9E
//   RX write  6E400002-B5A3-F393-E0A9-E50E24DCCA9E  (phone -> board)
//   TX notify 6E400003-B5A3-F393-E0A9-E50E24DCCA9E  (board -> phone)
// USB Serial stays up for debug. Phone stream:
//   ANG,L,<leftDeg>,R,<rightDeg>,DIFF,<absDiff>
// Phone commands (newline-terminated):
//   START
//   STOP
//   SETCOOLDOWN,<minutes>
//   CD,<minutes>
//   SETSLEEP,<HH:MM>,<HH:MM>   quiet hours; the Linux host owns the clock
//   SETSLEEP,OFF
//   SLEEP,1 / SLEEP,0          host-driven quiet latch, buzzers stay off while 1
//   c
//
// Conoin motors: do NOT connect a motor straight to an Arduino pin.
// Each motor: 3.3V -> motor+ -> motor- -> NPN collector; emitter to GND;
// Arduino D2 (left) / D3 (right) -> 1k -> NPN base; diode across motor.
// Hold still DOWN at start.

#include <Wire.h>
#include <math.h>
#include <stdlib.h>
#include <Arduino_RouterBridge.h>

static const uint8_t MPU_LEFT = 0x68;
static const uint8_t MPU_RIGHT = 0x69;

static const float ACCEL_LSB = 8192.0f;
static const float GYRO_LSB = 65.5f;
static const float DEG2RAD = 0.01745329252f;
static const float RAD2DEG = 57.29577951f;
static const float BETA_STILL = 0.20f;
static const float BETA_MOVE = 0.04f;
static const float STILL_A = 0.08f;
static const float STILL_G = 12.0f;
static const uint16_t HOLD_MS = 300;
static const uint32_t SAMPLE_US = 10000;  // 100 Hz; two sensors on one I2C bus
static const uint32_t PRINT_MS = 150;

// Observed raw pitch: 70 (down) -> 0 (up). Display: 0 -> 60, hard cap 100.
static const float RAW_PITCH_DOWN = 70.0f;
static const float RAW_PITCH_UP = 0.0f;
static const float DISPLAY_PITCH_SCALE = 60.0f;
static const float DISPLAY_PITCH_CAP = 100.0f;

static const uint8_t MOTOR_LEFT_PIN = 2;
static const uint8_t MOTOR_RIGHT_PIN = 3;
static const float MATCH_MIN_DEG = 60.0f;
static const float MATCH_MAX_DIFF_DEG = 5.0f;
static const float MATCH_HOLD_DEG = 50.0f;
static const float MOTOR_REARM_DEG = 20.0f;
static const uint16_t BURST_ON_MS = 90;
static const uint16_t BURST_GAP_MS = 90;
static const uint16_t BURST_PAUSE_MS = 450;

bool cueActive = false;
uint8_t cueStep = 0;
uint32_t cueStepMs = 0;
uint32_t cooldownMs = 60000;
uint32_t cooldownUntilMs = 0;
float cooldownMinutes = 1.0f;
bool matchPrinted = false;
bool waitForStart = true;

// Quiet hours latch. This MCU has no RTC, so python/main.py (Linux host, real clock)
// decides when the window is open and drives this flag. It is a fail-safe: while set,
// nothing here can energise a motor even if the host stops sending STOP.
bool sleepQuiet = false;

struct Imu {
  uint8_t addr;
  const char *name;
  bool present;
  float gxOff, gyOff, gzOff;
  float q0, q1, q2, q3;
  float pitchRaw;
  float pitchDeg;
  float peakPitch;
  bool holding;
  bool reported;
  uint32_t stillSinceMs;
  uint32_t lastUpdateUs;
  bool raisedOk;
};

Imu leftHand;
Imu rightHand;
uint32_t nextSampleUs = 0;
bool pairPrinted = false;
String lastAngLine = "ANG,L,nan,R,nan,DIFF,nan";

bool mpuWrite(uint8_t addr, uint8_t reg, uint8_t value) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool mpuRead14(uint8_t addr, int16_t *ax, int16_t *ay, int16_t *az,
               int16_t *gx, int16_t *gy, int16_t *gz) {
  for (uint8_t attempt = 0; attempt < 3; attempt++) {
    Wire.beginTransmission(addr);
    Wire.write(0x3B);
    if (Wire.endTransmission(false) != 0) {
      delayMicroseconds(50);
      continue;
    }
    if (Wire.requestFrom((int)addr, 14) != 14) {
      delayMicroseconds(50);
      continue;
    }
    *ax = (int16_t)((Wire.read() << 8) | Wire.read());
    *ay = (int16_t)((Wire.read() << 8) | Wire.read());
    *az = (int16_t)((Wire.read() << 8) | Wire.read());
    Wire.read();
    Wire.read();
    *gx = (int16_t)((Wire.read() << 8) | Wire.read());
    *gy = (int16_t)((Wire.read() << 8) | Wire.read());
    *gz = (int16_t)((Wire.read() << 8) | Wire.read());
    return true;
  }
  return false;
}

bool mpuBegin(uint8_t addr) {
  if (!mpuWrite(addr, 0x6B, 0x00)) {
    return false;
  }
  delay(10);
  mpuWrite(addr, 0x1B, 0x08);
  mpuWrite(addr, 0x1C, 0x08);
  mpuWrite(addr, 0x1A, 0x03);
  mpuWrite(addr, 0x19, 0x04);
  return true;
}

bool readRaw(Imu &imu, float &ax, float &ay, float &az, float &gx, float &gy, float &gz) {
  int16_t rax, ray, raz, rgx, rgy, rgz;
  if (!mpuRead14(imu.addr, &rax, &ray, &raz, &rgx, &rgy, &rgz)) {
    return false;
  }
  ax = rax / ACCEL_LSB;
  ay = ray / ACCEL_LSB;
  az = raz / ACCEL_LSB;
  gx = (rgx / GYRO_LSB) - imu.gxOff;
  gy = (rgy / GYRO_LSB) - imu.gyOff;
  gz = (rgz / GYRO_LSB) - imu.gzOff;
  return true;
}

void quatFromAccel(Imu &imu, float ax, float ay, float az) {
  float roll = atan2f(ay, az);
  float pitch = atan2f(-ax, sqrtf(ay * ay + az * az));
  float cr = cosf(roll * 0.5f);
  float sr = sinf(roll * 0.5f);
  float cp = cosf(pitch * 0.5f);
  float sp = sinf(pitch * 0.5f);
  imu.q0 = cr * cp;
  imu.q1 = sr * cp;
  imu.q2 = cr * sp;
  imu.q3 = -sr * sp;
}

void madgwickImu(Imu &imu, float gx, float gy, float gz, float ax, float ay, float az, float dt, float beta) {
  gx *= DEG2RAD;
  gy *= DEG2RAD;
  gz *= DEG2RAD;

  float q0 = imu.q0, q1 = imu.q1, q2 = imu.q2, q3 = imu.q3;
  float qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
  float qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy);
  float qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx);
  float qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

  float aNorm = sqrtf(ax * ax + ay * ay + az * az);
  if (aNorm > 0.2f) {
    float inv = 1.0f / aNorm;
    ax *= inv;
    ay *= inv;
    az *= inv;

    float _2q0 = 2.0f * q0;
    float _2q1 = 2.0f * q1;
    float _2q2 = 2.0f * q2;
    float _2q3 = 2.0f * q3;
    float _4q0 = 4.0f * q0;
    float _4q1 = 4.0f * q1;
    float _4q2 = 4.0f * q2;
    float _8q1 = 8.0f * q1;
    float _8q2 = 8.0f * q2;
    float q0q0 = q0 * q0;
    float q1q1 = q1 * q1;
    float q2q2 = q2 * q2;
    float q3q3 = q3 * q3;

    float s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay;
    float s1 = _4q1 * q3q3 - _2q3 * ax + 4.0f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az;
    float s2 = 4.0f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az;
    float s3 = 4.0f * q1q1 * q3 - _2q1 * ax + 4.0f * q2q2 * q3 - _2q2 * ay;
    float sn = sqrtf(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
    if (sn > 0.0f) {
      inv = 1.0f / sn;
      qDot0 -= beta * s0 * inv;
      qDot1 -= beta * s1 * inv;
      qDot2 -= beta * s2 * inv;
      qDot3 -= beta * s3 * inv;
    }
  }

  q0 += qDot0 * dt;
  q1 += qDot1 * dt;
  q2 += qDot2 * dt;
  q3 += qDot3 * dt;
  float qn = sqrtf(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
  if (qn > 0.0f) {
    float inv = 1.0f / qn;
    imu.q0 = q0 * inv;
    imu.q1 = q1 * inv;
    imu.q2 = q2 * inv;
    imu.q3 = q3 * inv;
  }
}

float mapPitch(float rawDeg) {
  float span = RAW_PITCH_DOWN - RAW_PITCH_UP;
  float mapped = (RAW_PITCH_DOWN - rawDeg) * (DISPLAY_PITCH_SCALE / span);
  if (mapped < 0.0f) {
    mapped = 0.0f;
  }
  if (mapped > DISPLAY_PITCH_CAP) {
    mapped = DISPLAY_PITCH_CAP;
  }
  return mapped;
}

void updatePitch(Imu &imu) {
  float q0 = imu.q0, q1 = imu.q1, q2 = imu.q2, q3 = imu.q3;
  float sp = 2.0f * (q0 * q2 - q3 * q1);
  if (sp > 1.0f) {
    sp = 1.0f;
  }
  if (sp < -1.0f) {
    sp = -1.0f;
  }
  imu.pitchRaw = asinf(sp) * RAD2DEG;
  imu.pitchDeg = mapPitch(imu.pitchRaw);
  if (imu.pitchDeg > imu.peakPitch) {
    imu.peakPitch = imu.pitchDeg;
  }
}

void resetTrial(Imu &imu) {
  imu.peakPitch = 0;
  imu.holding = false;
  imu.reported = false;
  imu.raisedOk = false;
}

void calibrate(Imu &imu) {
  const int n = 250;
  float sax = 0, say = 0, saz = 0, sgx = 0, sgy = 0, sgz = 0;
  imu.gxOff = 0;
  imu.gyOff = 0;
  imu.gzOff = 0;

  for (int i = 0; i < n; i++) {
    float ax, ay, az, gx, gy, gz;
    if (!readRaw(imu, ax, ay, az, gx, gy, gz)) {
      i--;
      delay(4);
      continue;
    }
    sax += ax;
    say += ay;
    saz += az;
    sgx += gx;
    sgy += gy;
    sgz += gz;
    delay(4);
  }

  imu.gxOff = sgx / n;
  imu.gyOff = sgy / n;
  imu.gzOff = sgz / n;
  quatFromAccel(imu, sax / n, say / n, saz / n);
  resetTrial(imu);
  imu.stillSinceMs = millis();
  imu.lastUpdateUs = 0;
}

void updateImu(Imu &imu) {
  float ax, ay, az, gx, gy, gz;
  if (!readRaw(imu, ax, ay, az, gx, gy, gz)) {
    return;
  }

  uint32_t nowUs = micros();
  float dt = SAMPLE_US / 1000000.0f;
  if (imu.lastUpdateUs != 0) {
    dt = (nowUs - imu.lastUpdateUs) / 1000000.0f;
  }
  imu.lastUpdateUs = nowUs;
  if (dt < 0.001f) {
    dt = 0.001f;
  }
  if (dt > 0.05f) {
    dt = 0.05f;
  }

  float aMag = sqrtf(ax * ax + ay * ay + az * az);
  float gMag = sqrtf(gx * gx + gy * gy + gz * gz);
  bool still = (fabsf(aMag - 1.0f) < STILL_A) && (gMag < STILL_G);
  float beta = still ? BETA_STILL : BETA_MOVE;

  madgwickImu(imu, gx, gy, gz, ax, ay, az, dt, beta);
  updatePitch(imu);
  if (imu.pitchDeg >= MATCH_MIN_DEG) {
    imu.raisedOk = true;
  }
  if (imu.pitchDeg < MOTOR_REARM_DEG) {
    imu.raisedOk = false;
  }

  uint32_t now = millis();
  if (still) {
    if (imu.stillSinceMs == 0) {
      imu.stillSinceMs = now;
    }
  } else {
    imu.stillSinceMs = 0;
    imu.holding = false;
    if (imu.reported && imu.pitchDeg < 12.0f) {
      resetTrial(imu);
      pairPrinted = false;
      Serial.print(imu.name);
      Serial.println(" ready");
    }
  }

  if (still && (now - imu.stillSinceMs) >= HOLD_MS && imu.pitchDeg > 20.0f && !imu.reported) {
    imu.holding = true;
    imu.reported = true;
    Serial.print(imu.name);
    Serial.print(" pitch: ");
    Serial.print(imu.pitchDeg, 1);
    Serial.print(" deg  (peak ");
    Serial.print(imu.peakPitch, 1);
    Serial.println(")");
  }
}

void driveMotors(bool on) {
  uint8_t level = on ? HIGH : LOW;
  digitalWrite(MOTOR_LEFT_PIN, level);
  digitalWrite(MOTOR_RIGHT_PIN, level);
}

void applyCooldownMinutes(float minutes) {
  if (minutes < 0.01f) {
    minutes = 0.01f;
  }
  if (minutes > 1440.0f) {
    minutes = 1440.0f;
  }
  cooldownMinutes = minutes;
  cooldownMs = (uint32_t)(minutes * 60000.0f + 0.5f);
  Serial.print("OK CD ");
  Serial.print(minutes, 2);
  Serial.println(" min");
}

void applySleepQuiet(bool quiet) {
  sleepQuiet = quiet;
  if (quiet) {
    waitForStart = true;
    stopCue();
  }
  Serial.print("OK SLEEP ");
  Serial.println(quiet ? 1 : 0);
}

void beginArmTest() {
  if (sleepQuiet) {
    // No buzzes at all during sleep hours, even if the phone asks.
    waitForStart = true;
    stopCue();
    Serial.println("OK START ignored (sleep hours)");
    return;
  }
  waitForStart = false;
  cooldownUntilMs = 0;
  matchPrinted = false;
  leftHand.raisedOk = false;
  rightHand.raisedOk = false;
  startCue();
  Serial.println("OK START");
}

bool startsIgnore(const char *line, const char *prefix) {
  while (*prefix) {
    char cl = *line;
    char cp = *prefix;
    if (cl >= 'A' && cl <= 'Z') {
      cl = (char)(cl + 32);
    }
    if (cp >= 'A' && cp <= 'Z') {
      cp = (char)(cp + 32);
    }
    if (cl != cp) {
      return false;
    }
    line++;
    prefix++;
  }
  return true;
}

bool eqIgnore(const char *a, const char *b) {
  while (*a && *b) {
    char ca = *a;
    char cb = *b;
    if (ca >= 'A' && ca <= 'Z') {
      ca = (char)(ca + 32);
    }
    if (cb >= 'A' && cb <= 'Z') {
      cb = (char)(cb + 32);
    }
    if (ca != cb) {
      return false;
    }
    a++;
    b++;
  }
  return *a == 0 && *b == 0;
}

void applyCommand(char *line) {
  while (*line == ' ' || *line == '\t') {
    line++;
  }
  if (line[0] == 0) {
    return;
  }

  if ((line[0] == 'c' || line[0] == 'C') && line[1] == 0) {
    Serial.println("Recalibrating...");
    delay(400);
    if (leftHand.present) {
      calibrate(leftHand);
    }
    if (rightHand.present) {
      calibrate(rightHand);
    }
    pairPrinted = false;
    matchPrinted = false;
    Serial.println("Ready");
    return;
  }

  // Both of these must be handled before the generic "SET...,<value>" branch below,
  // which would otherwise read SETSLEEP,22:00 as a 22 minute cooldown.
  if (startsIgnore(line, "SLEEP,")) {
    applySleepQuiet(atoi(line + 6) != 0);
    return;
  }

  if (startsIgnore(line, "SETSLEEP")) {
    // The Linux host owns the wall clock, so the window itself is enforced there and
    // this side only obeys the SLEEP latch. Acknowledge so the write is visible.
    Serial.println("OK SETSLEEP (host clock)");
    return;
  }

  if ((line[0] == 'c' || line[0] == 'C') &&
      (line[1] == 'd' || line[1] == 'D') &&
      line[2] == ',') {
    applyCooldownMinutes(atof(line + 3));
    return;
  }

  if ((line[0] == 's' || line[0] == 'S') &&
      (line[1] == 'e' || line[1] == 'E') &&
      (line[2] == 't' || line[2] == 'T')) {
    const char *comma = line;
    while (*comma && *comma != ',') {
      comma++;
    }
    if (*comma == ',') {
      applyCooldownMinutes(atof(comma + 1));
      return;
    }
  }

  if (eqIgnore(line, "START") || ((line[0] == 's' || line[0] == 'S') && line[1] == 0)) {
    beginArmTest();
    return;
  }

  if (eqIgnore(line, "STOP")) {
    waitForStart = true;
    stopCue();
    Serial.println("OK STOP");
    return;
  }
}

void pollCommands() {
  static char buf[48];
  static uint8_t n = 0;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (n == 0) {
        continue;
      }
      buf[n] = 0;
      n = 0;
      applyCommand(buf);
    } else if (n < sizeof(buf) - 1) {
      buf[n++] = c;
    } else {
      n = 0;
    }
  }
}

void startCue() {
  cueActive = true;
  cueStep = 0;
  cueStepMs = millis();
  driveMotors(true);
}

void stopCue() {
  cueActive = false;
  cueStep = 0;
  driveMotors(false);
}

void runCuePattern() {
  if (sleepQuiet) {
    if (cueActive) {
      stopCue();
    }
    return;
  }
  if (!cueActive) {
    return;
  }

  uint16_t dur = BURST_ON_MS;
  if (cueStep == 1) {
    dur = BURST_GAP_MS;
  } else if (cueStep == 3) {
    dur = BURST_PAUSE_MS;
  }

  if (millis() - cueStepMs < dur) {
    return;
  }

  cueStepMs = millis();
  cueStep = (uint8_t)((cueStep + 1) % 4);
  driveMotors(cueStep == 0 || cueStep == 2);
}

void updateMotors() {
  // Sleep hours win over everything else: stop a running cue and never start one.
  if (sleepQuiet) {
    if (cueActive) {
      stopCue();
    } else {
      driveMotors(false);
    }
    return;
  }

  if (!leftHand.present || !rightHand.present) {
    return;
  }

  float diff = fabsf(leftHand.pitchDeg - rightHand.pitchDeg);
  bool bothRaised = leftHand.raisedOk && rightHand.raisedOk;
  bool stillUp = (leftHand.pitchDeg >= MATCH_HOLD_DEG) &&
                 (rightHand.pitchDeg >= MATCH_HOLD_DEG);
  bool close = diff <= MATCH_MAX_DIFF_DEG;
  bool bothNowHigh = (leftHand.pitchDeg >= MATCH_MIN_DEG) &&
                     (rightHand.pitchDeg >= MATCH_MIN_DEG);

  if (cueActive && close && (bothNowHigh || (bothRaised && stillUp))) {
    stopCue();
    cooldownUntilMs = millis() + cooldownMs;
    if (!matchPrinted) {
      Serial.print("Match  L ");
      Serial.print(leftHand.pitchDeg, 1);
      Serial.print("  R ");
      Serial.print(rightHand.pitchDeg, 1);
      Serial.print("  next cue in ");
      Serial.print(cooldownMinutes, 2);
      Serial.println(" min");
      matchPrinted = true;
    }
    return;
  }

  if (!cueActive) {
    if (waitForStart) {
      return;
    }
    if (millis() < cooldownUntilMs) {
      return;
    }
    if (leftHand.pitchDeg < MOTOR_REARM_DEG &&
        rightHand.pitchDeg < MOTOR_REARM_DEG) {
      leftHand.raisedOk = false;
      rightHand.raisedOk = false;
      startCue();
      matchPrinted = false;
    }
  }

  runCuePattern();
}

void printFloatOrNan(bool present, float value) {
  if (present) {
    Serial.print(value, 1);
  } else {
    Serial.print("nan");
  }
}

void printBoth() {
  bool both = leftHand.present && rightHand.present;
  float diff = both ? fabsf(leftHand.pitchDeg - rightHand.pitchDeg) : 0.0f;

  lastAngLine = "ANG,L,";
  lastAngLine += leftHand.present ? String(leftHand.pitchDeg, 1) : "nan";
  lastAngLine += ",R,";
  lastAngLine += rightHand.present ? String(rightHand.pitchDeg, 1) : "nan";
  lastAngLine += ",DIFF,";
  lastAngLine += both ? String(diff, 1) : "nan";
  Serial.println(lastAngLine);
}

String rpcGetLine() {
  return lastAngLine;
}

String rpcCommand(String line) {
  applyCommand(const_cast<char *>(line.c_str()));
  return String("ok");
}

void setup() {
  Serial.begin(115200);
  Bridge.begin();
  Bridge.provide_safe("get_line", rpcGetLine);
  Bridge.provide_safe("command", rpcCommand);
  pinMode(MOTOR_LEFT_PIN, OUTPUT);
  pinMode(MOTOR_RIGHT_PIN, OUTPUT);
  stopCue();
  Wire.begin();
  Wire.setClock(400000);
  delay(200);

  leftHand.addr = MPU_LEFT;
  leftHand.name = "LEFT";
  leftHand.q0 = 1;
  rightHand.addr = MPU_RIGHT;
  rightHand.name = "RIGHT";
  rightHand.q0 = 1;

  Serial.println("GY-521 pitch");
  Serial.println("Hold still in the DOWN pose...");

  leftHand.present = mpuBegin(MPU_LEFT);
  rightHand.present = mpuBegin(MPU_RIGHT);

  if (!leftHand.present && !rightHand.present) {
    Serial.println("No MPU-6050 on 0x68 or 0x69.");
    while (true) {
      delay(1000);
    }
  }

  delay(300);
  if (leftHand.present) {
    Serial.println("Calibrating LEFT...");
    calibrate(leftHand);
    Serial.println("LEFT ready");
  }
  if (rightHand.present) {
    Serial.println("Calibrating RIGHT...");
    calibrate(rightHand);
    Serial.println("RIGHT ready");
  }

  Serial.println("UNO Q BLE: run python/main.py. USB serial still works.");
  Serial.println("Commands: START, STOP, SETCOOLDOWN,<min>, SETSLEEP,<HH:MM>,<HH:MM>, c");
  applyCooldownMinutes(1.0f);
  cooldownUntilMs = 0;
  matchPrinted = false;
  nextSampleUs = micros();
}

void loop() {
  pollCommands();
  runCuePattern();
  updateMotors();

  uint32_t nowUs = micros();
  if ((int32_t)(nowUs - nextSampleUs) < 0) {
    return;
  }
  nextSampleUs += SAMPLE_US;
  if ((int32_t)(nowUs - nextSampleUs) > (int32_t)(SAMPLE_US * 8)) {
    nextSampleUs = nowUs + SAMPLE_US;
  }

  if (leftHand.present) {
    updateImu(leftHand);
  }
  if (rightHand.present) {
    updateImu(rightHand);
  }

  updateMotors();

  static uint32_t lastPrint = 0;
  if (millis() - lastPrint >= PRINT_MS) {
    lastPrint = millis();
    printBoth();
  }
}
