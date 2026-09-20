#!/usr/bin/env python3
"""UNO Q onboard BLE bridge for StrokeSense.

STM32 sketch owns IMUs/buzzers. This Linux process:
  - Polls Bridge.get_line() for ANG,L,... frames
  - Advertises Nordic UART Service (no HC-05)
  - Forwards START / STOP / SETCOOLDOWN,<min> to the MCU
  - Owns sleep hours (SETSLEEP,<HH:MM>,<HH:MM>): the MCU has no RTC, this host does,
    so quiet hours are enforced here and mirrored to the MCU as a SLEEP,1/0 latch

UUIDs:
  6E400001-B5A3-F393-E0A9-E50E24DCCA9E  service
  6E400002-B5A3-F393-E0A9-E50E24DCCA9E  RX write  (phone -> board)
  6E400003-B5A3-F393-E0A9-E50E24DCCA9E  TX notify (board -> phone)

BlueZ is driven over GDBus (gi.repository.Gio). The UNO Q image ships python3-gi but
not python3-dbus, and the board has no network to install it.
"""

from __future__ import annotations

import itertools
import os
import sys
import threading
import time

NAME = "StrokeSense"
NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

# Cold battery boots start this process before bluetoothd is ready, so every stage
# waits instead of failing once.
BLUEZ_WAIT_S = 45
POWER_WAIT_S = 15
RETRY_DELAY_S = 5
CALL_TIMEOUT_MS = 15000

# The MCU stream is ~7 Hz, so echoing every frame is only useful while debugging.
DEBUG_MCU = os.environ.get("STROKESENSE_DEBUG_MCU", "") not in ("", "0")

BLUEZ = "org.bluez"
ADAPTER_IFACE = "org.bluez.Adapter1"
GATT_MGR_IFACE = "org.bluez.GattManager1"
LE_AD_MGR_IFACE = "org.bluez.LEAdvertisingManager1"
GATT_SVC_IFACE = "org.bluez.GattService1"
GATT_CHR_IFACE = "org.bluez.GattCharacteristic1"
LE_AD_IFACE = "org.bluez.LEAdvertisement1"
OM_IFACE = "org.freedesktop.DBus.ObjectManager"
PROP_IFACE = "org.freedesktop.DBus.Properties"

try:
    from gi.repository import Gio, GLib
except ImportError:  # pragma: no cover - reported through main()'s error path
    Gio = None
    GLib = None

try:
    from arduino.app_utils import App, Bridge
except ImportError:
    App = None
    Bridge = None


def mcu_get_line() -> str:
    if Bridge is None:
        return ""
    try:
        value = Bridge.call("get_line")
        return "" if value is None else str(value)
    except Exception as exc:  # noqa: BLE001
        print("get_line failed:", exc, file=sys.stderr)
        return ""


def mcu_command(line: str) -> None:
    text = (line or "").strip()
    if not text or Bridge is None:
        return
    try:
        Bridge.call("command", text)
        print("MCU command:", text)
    except Exception as exc:  # noqa: BLE001
        print("command failed:", exc, file=sys.stderr)


def parse_hhmm(text: str):
    """Minutes past midnight for '22:00' or '2200', else None."""
    raw = (text or "").strip()
    if not raw:
        return None
    if ":" in raw:
        hour_text, _, minute_text = raw.partition(":")
    elif raw.isdigit() and len(raw) in (3, 4):
        hour_text, minute_text = raw[:-2], raw[-2:]
    else:
        return None
    try:
        hour = int(hour_text)
        minute = int(minute_text)
    except ValueError:
        return None
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return hour * 60 + minute


def fmt_hhmm(minutes: int) -> str:
    return "%02d:%02d" % (minutes // 60 % 24, minutes % 60)


class SleepWindow:
    """Quiet hours. The STM32 has no clock, so this host is the authority.

    The window survives a service restart through [STATE_PATH] because the phone is
    not necessarily around at 22:00 to re-send it.
    """

    STATE_PATH = os.environ.get(
        "STROKESENSE_SLEEP_FILE",
        os.path.join(os.path.expanduser("~"), ".strokesense_sleep"),
    )

    def __init__(self):
        self._lock = threading.Lock()
        self.start = None
        self.end = None
        self._load()

    def _load(self):
        try:
            with open(self.STATE_PATH, "r", encoding="utf-8") as handle:
                saved = handle.read().strip()
        except OSError:
            return
        start, _, end = saved.partition(",")
        self.set(start, end, persist=False)

    def _save(self):
        text = "" if self.start is None else "%s,%s" % (fmt_hhmm(self.start), fmt_hhmm(self.end))
        try:
            with open(self.STATE_PATH, "w", encoding="utf-8") as handle:
                handle.write(text)
        except OSError as exc:
            print("could not persist sleep window:", exc, file=sys.stderr)

    def set(self, start_text, end_text, persist=True) -> bool:
        """Applies a window. An unusable or zero-length pair turns quiet hours off."""
        start = parse_hhmm(start_text)
        end = parse_hhmm(end_text)
        off = start is None or end is None or start == end
        with self._lock:
            self.start = None if off else start
            self.end = None if off else end
        if persist:
            self._save()
        return not off

    @property
    def enabled(self) -> bool:
        return self.start is not None

    def describe(self) -> str:
        if not self.enabled:
            return "off"
        return "%s-%s" % (fmt_hhmm(self.start), fmt_hhmm(self.end))

    def asleep(self, now=None) -> bool:
        with self._lock:
            start, end = self.start, self.end
        if start is None or end is None:
            return False
        stamp = now or time.localtime()
        minute = stamp.tm_hour * 60 + stamp.tm_min
        if start < end:
            return start <= minute < end
        return minute >= start or minute < end  # overnight window


sleep_window = SleepWindow()
_tx_channel = None


def notify_phone(text: str) -> None:
    channel = _tx_channel
    if channel is None:
        return
    try:
        channel.notify(text)
    except Exception as exc:  # noqa: BLE001
        print("notify failed:", exc, file=sys.stderr)


def sleep_status_line() -> str:
    return "SLEEP,%d,%s" % (1 if sleep_window.asleep() else 0, sleep_window.describe())


def handle_phone_line(line: str) -> None:
    """RX writes from the phone. Sleep hours are filtered here, not on the MCU."""
    text = (line or "").strip()
    if not text:
        return
    upper = text.upper()

    if upper.startswith("SETSLEEP"):
        parts = text.split(",")
        start = parts[1] if len(parts) > 1 else ""
        end = parts[2] if len(parts) > 2 else ""
        on = sleep_window.set(start, end)
        print("sleep hours:", sleep_window.describe())
        apply_sleep_state(force=True)
        if not on:
            mcu_command("SLEEP,0")
        notify_phone(sleep_status_line())
        return

    if upper in ("START", "S") and sleep_window.asleep():
        print("START refused: inside sleep hours", sleep_window.describe())
        mcu_command("STOP")
        notify_phone(sleep_status_line())
        return

    mcu_command(text)


_last_sleep_state = None
_last_quiet_nudge = 0.0

# While asleep the MCU is re-told to stay quiet on this period, so a dropped Bridge
# call cannot leave a buzzer running for the rest of the night.
QUIET_NUDGE_S = 30.0


def apply_sleep_state(force: bool = False) -> None:
    global _last_sleep_state, _last_quiet_nudge
    asleep = sleep_window.asleep()
    changed = asleep != _last_sleep_state
    if changed or force:
        _last_sleep_state = asleep
        mcu_command("SLEEP,1" if asleep else "SLEEP,0")
        if asleep:
            mcu_command("STOP")
            _last_quiet_nudge = time.time()
        if changed:
            print("sleep hours now", "ACTIVE" if asleep else "clear", sleep_window.describe())
            notify_phone(sleep_status_line())
        return
    if asleep and time.time() - _last_quiet_nudge >= QUIET_NUDGE_S:
        _last_quiet_nudge = time.time()
        mcu_command("SLEEP,1")
        mcu_command("STOP")


def watch_sleep_window():
    while True:
        try:
            apply_sleep_state()
        except Exception as exc:  # noqa: BLE001
            print("sleep watchdog error:", exc, file=sys.stderr)
        time.sleep(5.0)


OM_XML = """
<node>
  <interface name='org.freedesktop.DBus.ObjectManager'>
    <method name='GetManagedObjects'>
      <arg name='objects' type='a{oa{sa{sv}}}' direction='out'/>
    </method>
  </interface>
</node>
"""

SERVICE_XML = """
<node>
  <interface name='org.bluez.GattService1'>
    <property name='UUID' type='s' access='read'/>
    <property name='Primary' type='b' access='read'/>
    <property name='Characteristics' type='ao' access='read'/>
  </interface>
</node>
"""

CHAR_XML = """
<node>
  <interface name='org.bluez.GattCharacteristic1'>
    <property name='UUID' type='s' access='read'/>
    <property name='Service' type='o' access='read'/>
    <property name='Flags' type='as' access='read'/>
    <property name='Value' type='ay' access='read'/>
    <property name='Notifying' type='b' access='read'/>
    <method name='ReadValue'>
      <arg name='options' type='a{sv}' direction='in'/>
      <arg name='value' type='ay' direction='out'/>
    </method>
    <method name='WriteValue'>
      <arg name='value' type='ay' direction='in'/>
      <arg name='options' type='a{sv}' direction='in'/>
    </method>
    <method name='StartNotify'/>
    <method name='StopNotify'/>
  </interface>
</node>
"""


def _dbus_name(exc) -> str:
    """D-Bus error name if BlueZ sent one, else the plain GLib message."""
    remote = Gio.DBusError.get_remote_error(exc) if Gio is not None else None
    return remote or getattr(exc, "message", str(exc))


def _register(bus, path, xml, method_cb=None, get_prop_cb=None):
    info = Gio.DBusNodeInfo.new_for_xml(xml).interfaces[0]
    try:
        return bus.register_object(path, info, method_cb, get_prop_cb, None)
    except TypeError:
        # Older PyGObject only exposes the explicit closure variant.
        return bus.register_object_with_closures(path, info, method_cb, get_prop_cb, None)


class _Characteristic:
    def __init__(self, bus, service_path, index, uuid, flags):
        self.bus = bus
        self.service_path = service_path
        self.path = "%s/char%d" % (service_path, index)
        self.uuid = uuid
        self.flags = flags
        self.value = b""
        self.notifying = False
        self.reg_id = _register(bus, self.path, CHAR_XML, self._method, self._get_prop)

    def props(self):
        return {
            "UUID": GLib.Variant("s", self.uuid),
            "Service": GLib.Variant("o", self.service_path),
            "Flags": GLib.Variant("as", self.flags),
            "Value": GLib.Variant("ay", self.value),
            "Notifying": GLib.Variant("b", self.notifying),
        }

    def _get_prop(self, connection, sender, path, iface, name):
        return self.props().get(name)

    def _method(self, connection, sender, path, iface, method, params, invocation):
        if method == "ReadValue":
            invocation.return_value(GLib.Variant("(ay)", (self.value,)))
        elif method == "WriteValue":
            payload = bytes(params.unpack()[0]).decode("utf-8", errors="ignore")
            for piece in payload.replace("\r", "\n").split("\n"):
                handle_phone_line(piece)
            invocation.return_value(None)
        elif method == "StartNotify":
            self.notifying = True
            invocation.return_value(None)
        elif method == "StopNotify":
            self.notifying = False
            invocation.return_value(None)
        else:
            invocation.return_dbus_error("org.bluez.Error.NotSupported", method)

    def notify(self, text: str):
        if not self.notifying:
            return
        data = text.encode("utf-8")
        if not data.endswith(b"\n"):
            data += b"\n"
        self.value = data
        self.bus.emit_signal(
            None,
            self.path,
            PROP_IFACE,
            "PropertiesChanged",
            GLib.Variant(
                "(sa{sv}as)",
                (GATT_CHR_IFACE, {"Value": GLib.Variant("ay", data)}, []),
            ),
        )


class _Service:
    def __init__(self, bus, root):
        self.bus = bus
        self.path = root + "/service0"
        self.chars = []
        self.reg_id = _register(bus, self.path, SERVICE_XML, None, self._get_prop)

    def props(self):
        return {
            "UUID": GLib.Variant("s", NUS),
            "Primary": GLib.Variant("b", True),
            "Characteristics": GLib.Variant("ao", [c.path for c in self.chars]),
        }

    def _get_prop(self, connection, sender, path, iface, name):
        return self.props().get(name)


class _Application:
    """Root ObjectManager BlueZ walks to discover the service and characteristics."""

    def __init__(self, bus, root):
        self.bus = bus
        self.path = root
        self.service = None
        self.reg_id = _register(bus, root, OM_XML, self._method, None)

    def managed(self):
        out = {self.service.path: {GATT_SVC_IFACE: self.service.props()}}
        for char in self.service.chars:
            out[char.path] = {GATT_CHR_IFACE: char.props()}
        return out

    def _method(self, connection, sender, path, iface, method, params, invocation):
        if method == "GetManagedObjects":
            invocation.return_value(GLib.Variant("(a{oa{sa{sv}}})", (self.managed(),)))
        else:
            invocation.return_dbus_error("org.bluez.Error.NotSupported", method)


class _Advertisement:
    def __init__(self, bus, root, index, props):
        self.bus = bus
        self.path = root + "/advertisement%d" % index
        self._props = props
        self.reg_id = _register(bus, self.path, self._xml(props), self._method, self._get_prop)

    @staticmethod
    def _xml(props):
        # Only declare the properties this variant actually carries: GDBus answers
        # Properties.GetAll from the introspection data, and BlueZ aborts the whole
        # registration if a declared property cannot be read.
        decls = "".join(
            "<property name='%s' type='%s' access='read'/>" % (key, value.get_type_string())
            for key, value in props.items()
        )
        return (
            "<node><interface name='%s'><method name='Release'/>%s</interface></node>"
            % (LE_AD_IFACE, decls)
        )

    def _get_prop(self, connection, sender, path, iface, name):
        return self._props.get(name)

    def _method(self, connection, sender, path, iface, method, params, invocation):
        if method == "Release":
            invocation.return_value(None)
        else:
            invocation.return_dbus_error("org.bluez.Error.NotSupported", method)


def start_bluez_nus(attempt: int = 0):
    if Gio is None:
        raise RuntimeError("python3-gi (PyGObject) is required for the BlueZ GATT server")

    bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)

    def call(path, iface, method, args=None, reply=None):
        return bus.call_sync(
            BLUEZ,
            path,
            iface,
            method,
            args,
            GLib.VariantType(reply) if reply else None,
            Gio.DBusCallFlags.NONE,
            CALL_TIMEOUT_MS,
            None,
        )

    # On a cold battery boot this process can start before bluetoothd owns the name.
    adapter = None
    deadline = time.time() + BLUEZ_WAIT_S
    while time.time() < deadline:
        try:
            managed = call("/", OM_IFACE, "GetManagedObjects", None, "(a{oa{sa{sv}}})")
            adapter = next(
                (p for p, i in managed.unpack()[0].items() if LE_AD_MGR_IFACE in i),
                None,
            )
        except GLib.Error as exc:
            print("waiting for BlueZ:", _dbus_name(exc), file=sys.stderr)
        if adapter is not None:
            break
        time.sleep(1.0)
    if adapter is None:
        raise RuntimeError("No BLE adapter after %ds" % BLUEZ_WAIT_S)

    # RegisterAdvertisement fails outright while the controller is down, which is the
    # normal state after a boot with no prior bluetoothctl session.
    def powered():
        reply = call(
            adapter,
            PROP_IFACE,
            "Get",
            GLib.Variant("(ss)", (ADAPTER_IFACE, "Powered")),
            "(v)",
        )
        return bool(reply.unpack()[0])

    try:
        for prop, value in (("Powered", GLib.Variant("b", True)), ("Alias", GLib.Variant("s", NAME))):
            call(
                adapter,
                PROP_IFACE,
                "Set",
                GLib.Variant("(ssv)", (ADAPTER_IFACE, prop, value)),
            )
    except GLib.Error as exc:
        print("adapter power/alias failed:", _dbus_name(exc), file=sys.stderr)
    for _ in range(int(POWER_WAIT_S * 2)):
        if powered():
            break
        time.sleep(0.5)
    if not powered():
        raise RuntimeError("Adapter %s will not power on" % adapter)
    print("adapter", adapter, "powered")

    # Each retry exports fresh objects, so the paths must not collide with a previous
    # attempt that is still registered on this bus connection.
    root = "/com/hackmit/strokesense/try%d" % attempt

    app = _Application(bus, root)
    service = _Service(bus, root)
    tx = _Characteristic(bus, service.path, 0, TX_UUID, ["notify", "read"])
    rx = _Characteristic(bus, service.path, 1, RX_UUID, ["write", "write-without-response"])
    service.chars.extend([tx, rx])
    app.service = service

    call(adapter, GATT_MGR_IFACE, "RegisterApplication", GLib.Variant("(oa{sv})", (root, {})))
    print("GATT application registered at", root)

    # Flags + a 128-bit UUID + TX power + "StrokeSense" does not always fit the 31-byte
    # legacy advertising payload, and BlueZ rejects the whole registration when it
    # overflows. Drop the optional fields before giving up; the service UUID matters
    # most because the phone scans by UUID first, then by name.
    variants = [
        {
            "Type": GLib.Variant("s", "peripheral"),
            "ServiceUUIDs": GLib.Variant("as", [NUS]),
            "LocalName": GLib.Variant("s", NAME),
            "Discoverable": GLib.Variant("b", True),
            "IncludeTxPower": GLib.Variant("b", True),
        },
        {
            "Type": GLib.Variant("s", "peripheral"),
            "ServiceUUIDs": GLib.Variant("as", [NUS]),
            "LocalName": GLib.Variant("s", NAME),
        },
        {
            "Type": GLib.Variant("s", "peripheral"),
            "ServiceUUIDs": GLib.Variant("as", [NUS]),
        },
        {
            "Type": GLib.Variant("s", "peripheral"),
            "LocalName": GLib.Variant("s", NAME),
        },
    ]

    last_error = None
    for index, props in enumerate(variants):
        adv = _Advertisement(bus, root, index, props)
        try:
            call(
                adapter,
                LE_AD_MGR_IFACE,
                "RegisterAdvertisement",
                GLib.Variant("(oa{sv})", (adv.path, {})),
            )
        except GLib.Error as exc:
            last_error = exc
            print(
                "advertisement variant %d rejected: %s" % (index, _dbus_name(exc)),
                file=sys.stderr,
            )
            bus.unregister_object(adv.reg_id)
            continue
        print("BLE advertising as", NAME, "on", adapter, "(variant %d)" % index)
        print("  service", NUS)
        return tx

    # Leave nothing registered behind, or the next attempt's adapter state is unclear.
    try:
        call(adapter, GATT_MGR_IFACE, "UnregisterApplication", GLib.Variant("(o)", (root,)))
    except GLib.Error:
        pass
    for obj in (tx, rx, service, app):
        bus.unregister_object(obj.reg_id)
    raise RuntimeError("RegisterAdvertisement rejected: %s" % _dbus_name(last_error))


_attempts = itertools.count()


def run_ble_and_poll():
    # BlueZ calls back into our exported objects while RegisterApplication is still
    # outstanding, so the bring-up cannot happen on the thread that owns the main loop:
    # a blocking call there would starve the very handlers BlueZ is waiting on and
    # RegisterApplication would time out. The loop runs here, bring-up runs alongside.
    loop = GLib.MainLoop()
    loop_ready = threading.Event()
    failure = []

    def mark_ready():
        loop_ready.set()
        return False

    def poll(tx):
        # get_line returns the MCU's latest angle snapshot, not a queue, so repeats are
        # expected and two pollers do not steal frames from each other.
        global _tx_channel
        _tx_channel = tx
        # Push the current quiet state as soon as the MCU is reachable, so a window that
        # was already open before this process started is honoured immediately.
        apply_sleep_state(force=True)
        threading.Thread(target=watch_sleep_window, daemon=True).start()
        linked = False
        while True:
            line = mcu_get_line().strip()
            if line:
                if not linked:
                    linked = True
                    print("MCU link live:", line)
                elif DEBUG_MCU:
                    print("MCU:", line)
                try:
                    tx.notify(line)
                except Exception as exc:  # noqa: BLE001
                    print("notify failed:", exc, file=sys.stderr)
            time.sleep(0.15)

    def bring_up():
        loop_ready.wait(30)
        for tries in range(10):
            try:
                tx = start_bluez_nus(next(_attempts))
            except Exception as exc:  # noqa: BLE001
                print(
                    "BLE setup attempt %d failed: %s" % (tries + 1, exc),
                    file=sys.stderr,
                )
                if tries == 9:
                    failure.append(exc)
                    GLib.idle_add(loop.quit)
                    return
                time.sleep(RETRY_DELAY_S)
                continue
            poll(tx)
            return

    GLib.idle_add(mark_ready)
    threading.Thread(target=bring_up, daemon=True).start()
    loop.run()
    if failure:
        raise failure[0]


def main():
    # Without line buffering the App Lab log stays empty until the process exits,
    # which makes a failing boot look like nothing ran at all.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(line_buffering=True)
        except AttributeError:
            pass
    print("StrokeSense UNO Q BLE bridge", flush=True)
    try:
        run_ble_and_poll()
    except Exception as exc:  # noqa: BLE001
        print("BlueZ GATT server failed:", exc, file=sys.stderr)
        print(
            "Run python/main.py on the UNO Q Debian host if App Lab cannot reach BlueZ. "
            "Check: systemctl status bluetooth, then bluetoothctl show.",
            file=sys.stderr,
        )
        if App is None:
            raise

        # Keep the App Lab process alive but keep trying, so the board can still start
        # advertising once BlueZ becomes reachable.
        def retry_forever():
            while True:
                time.sleep(30)
                try:
                    run_ble_and_poll()
                except Exception as retry_exc:  # noqa: BLE001
                    print("BLE retry failed:", retry_exc, file=sys.stderr)

        threading.Thread(target=retry_forever, daemon=True).start()

        def tick():
            time.sleep(1)

        App.run(tick)


if __name__ == "__main__":
    main()
