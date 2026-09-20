import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest
from gateway.risk import assess


def m(outcome, score=0.0, quality=1.0):
    return {"score": score, "outcome": outcome, "quality": quality}


def r(modules, ctx=None, policy="sensitive"):
    return assess(modules, ctx or {}, policy)


def test_all_normal_monitor():
    a = r({"face": m("normal", 0.1), "speech": m("normal", 0.2), "motor": m("normal", 0.1)})
    assert a["action"] == "MONITOR"
    assert a["signs"] == []


def test_two_signs_911():
    a = r(
        {"face": m("abnormal", 0.8), "speech": m("abnormal", 0.9)},
        {"onset_minutes": 120, "abrupt": True},
    )
    assert a["action"] == "CALL_911"
    assert a["time_critical"] is True
    assert a["clinical"]["cpss"] == 2


def test_one_sign_in_window_abrupt_911():
    a = r({"face": m("abnormal", 0.8)}, {"onset_minutes": 45, "abrupt": True})
    assert a["action"] == "CALL_911"


def test_one_sign_late_onset_caregiver():
    a = r({"face": m("abnormal", 0.8)}, {"onset_minutes": 600, "abrupt": True})
    assert a["action"] == "ALERT_CAREGIVER"


def test_one_sign_inability_not_auto_911():
    a = r({"motor": m("unable")}, {"onset_minutes": 45, "abrupt": False})
    assert a["action"] == "ALERT_CAREGIVER"
    assert a["inability_count"] == 1


def test_no_signs_high_score_caregiver():
    a = r({"speech": m("normal", 0.6)}, {"onset_minutes": 60})
    assert a["action"] == "ALERT_CAREGIVER"


def test_no_signs_low_score_monitor():
    a = r({"speech": m("normal", 0.1)}, {"onset_minutes": 60})
    assert a["action"] == "MONITOR"


def test_balanced_policy_less_sensitive():
    a = r({"speech": m("normal", 0.4)}, {}, policy="balanced")
    assert a["action"] == "MONITOR"
    b = r({"speech": m("normal", 0.4)}, {}, policy="sensitive")
    assert b["action"] == "ALERT_CAREGIVER"


def test_severity_ordering():
    low = r({"face": m("abnormal", 0.5)})
    high = r({"face": m("abnormal", 0.9), "speech": m("unable")})
    assert high["severity"] > low["severity"]
    assert high["p_stroke"] > low["p_stroke"]


def test_rationale_present():
    a = r({"face": m("abnormal", 0.8)}, {"onset_minutes": 45, "abrupt": True})
    assert a["rationale"]
    assert any("4.5 h" in x for x in a["rationale"])