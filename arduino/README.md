# Arduino UNO Q firmware (StrokeSense)

MCU sketch plus the Debian-host BLE bridge for the StrokeSense arm-raise board.

This is **UNO Q onboard BLE** (Qualcomm / Linux BlueZ). There is **no HC-05** and no Classic Bluetooth SPP.

## Layout (Arduino App Lab)

```
sketch/sketch.ino                 STM32 firmware — paste or open this in App Lab
python/main.py                    BLE bridge — run on the Debian **host**, not in the App Lab container
systemd/strokesense-ble.service   systemd user unit (`strokesense-ble.service`)
```

The App Lab container cannot talk to BlueZ (no system D-Bus, no `bluetooth` group). `python/main.py` belongs on the host; the unit starts it at boot.

## Setup

Follow **[Connecting the Arduino IMU](../README.md#connecting-the-arduino-imu)** in the repo root README. In short:

1. App Lab: open or paste the files from this folder (`sketch/sketch.ino`, optionally `python/main.py`).
2. Install `systemd/strokesense-ble.service` on the Debian host so the bridge advertises as `StrokeSense` at startup.
3. Phone: Settings → Arduino IMU → **Uno Q BLE** (or **Mock data**) → scan or paste the MAC. The board does **not** appear in Android Bluetooth settings.
4. Motor screens send `SETSLEEP`, `START` / `STOP`, and `SETCOOLDOWN`.
