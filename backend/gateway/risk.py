"""Stroke risk decision layer (v0, clinically-informed but NOT clinically validated).

Consumes per-module screening results (face / speech / motor) plus context and
returns:
  - p_stroke   : 0..1 likelihood of acute stroke/TIA
  - severity   : 0..1 ordinal urgency
  - action     : CALL_911 | ALERT_CAREGIVER | MONITOR
  - time_critical : True when within the thrombolysis window
  - rationale  : human-readable reasons

Design (see plan):
  - Clinical scales (CPSS, ROSIER, FAST-ED partial) act as an expert teacher.
  - The learned model may only ESCALATE above the clinical rule, never de-escalate
    a clinically positive result (safety-first).
  - "Unable to complete" a FAST item counts as a positive sign (FAST convention),
    but is not alone sufficient for 911 (policy decided with the team).
  - Two operating points: sensitive (home default) and balanced.
"""
from __future__ import annotations

import logging

log = logging.getLogger("strokesense.gateway.risk")

# Outcomes a module can report.
NORMAL, ABNORMAL, UNABLE, TIMEOUT, ERROR, MISSING = (
    "normal",
    "abnormal",
    "unable",
    "timeout",
    "error",
    "missing",
)
POSITIVE_OUTCOMES = {ABNORMAL, UNABLE, TIMEOUT}

# ~72% stroke probability for a single positive CPSS item (Kothari 1999).
CPSS_PROB = {0: 0.05, 1: 0.55, 2: 0.85, 3: 0.95}
THROMBOLYSIS_WINDOW_MIN = 270  # 4.5 h


def _signs(modules: dict) -> list[str]:
    """Modules whose outcome is a positive FAST sign."""
    return [name for name, m in modules.items() if m.get("outcome", NORMAL) in POSITIVE_OUTCOMES]


def _inability_count(modules: dict) -> int:
    return sum(1 for m in modules.values() if m.get("outcome") in (UNABLE, TIMEOUT))


def clinical_scores(modules: dict) -> dict:
    """CPSS / ROSIER / FAST-ED (partial: face, arm, speech only)."""
    face = modules.get("face", {})
    speech = modules.get("speech", {})
    motor = modules.get("motor", {})
    f_abn = 1 if face.get("outcome", NORMAL) in POSITIVE_OUTCOMES else 0
    a_abn = 1 if motor.get("outcome", NORMAL) in POSITIVE_OUTCOMES else 0
    s_abn = 1 if speech.get("outcome", NORMAL) in POSITIVE_OUTCOMES else 0
    return {
        "cpss": f_abn + a_abn + s_abn,
        "roisier": f_abn + a_abn + s_abn,  # + visual/leg not measured here
        "fast_ed_partial": f_abn + a_abn + s_abn,  # face/arm/speech items only
    }


def p_from_clinical(cpss: int, max_score: float) -> float:
    """Heuristic probability from the clinical teacher + best module score."""
    p_model = float(max(0.0, min(1.0, max_score)))
    if cpss <= 0:
        # No clinical sign, but a strong continuous score still raises concern.
        return max(0.0, min(1.0, 0.05 + 0.45 * p_model))
    p_clin = CPSS_PROB[min(cpss, 3)]
    return max(0.0, min(1.0, 0.65 * p_clin + 0.35 * p_model))


def severity_of(signs: list[str], inability: int, max_score: float) -> float:
    if not signs:
        return 0.0
    sev = 0.45 * min(len(signs), 3) / 3.0 + 0.25 * float(max(0.0, min(1.0, max_score))) + 0.30 * min(inability, 3) / 3.0
    return round(max(0.0, min(1.0, sev)), 3)


def decide(p_stroke: float, signs: list[str], inability: int, context: dict, policy: str = "sensitive") -> str:
    """Map (p_stroke, signs, context) -> action via the approved v0 policy table.

    Sign-driven rules take precedence:
      - >= 2 positive signs                        -> CALL_911
      - 1 sign + onset < 4.5 h + abrupt            -> CALL_911
      - 1 sign otherwise                           -> ALERT_CAREGIVER (+ recheck)
      - 0 signs but elevated p_stroke              -> ALERT_CAREGIVER (+ retest)
      - otherwise                                  -> MONITOR
    Onset within the thrombolysis window lowers the p threshold (x0.7).
    """
    onset = context.get("onset_minutes")
    abrupt = bool(context.get("abrupt", False))
    in_window = onset is not None and onset < THROMBOLYSIS_WINDOW_MIN

    if len(signs) >= 2:
        return "CALL_911"
    if len(signs) == 1:
        if in_window and abrupt:
            return "CALL_911"
        return "ALERT_CAREGIVER"

    # 0 positive signs: use the continuous model probability only to reach a caregiver.
    caregiver_at = 0.15 if policy == "sensitive" else 0.30
    if in_window:
        caregiver_at *= 0.7
    return "ALERT_CAREGIVER" if p_stroke >= caregiver_at else "MONITOR"


def assess(modules: dict, context: dict, policy: str = "sensitive") -> dict:
    ctx = context or {}
    signs = _signs(modules)
    inability = _inability_count(modules)
    scores = [m.get("score", 0.0) for m in modules.values() if isinstance(m.get("score"), (int, float))]
    max_score = max(scores) if scores else 0.0

    clin = clinical_scores(modules)
    p_stroke = p_from_clinical(clin["cpss"], max_score)
    severity = severity_of(signs, inability, max_score)
    action = decide(p_stroke, signs, inability, ctx, policy)

    onset = ctx.get("onset_minutes")
    time_critical = bool(onset is not None and onset < THROMBOLYSIS_WINDOW_MIN and signs)

    rationale: list[str] = []
    if len(signs) >= 2:
        rationale.append(f"{len(signs)} positive FAST signs ({', '.join(signs)})")
    elif len(signs) == 1:
        rationale.append(f"1 positive FAST sign ({signs[0]})")
    if inability:
        rationale.append(f"{inability} test(s) could not be completed")
    if time_critical:
        rationale.append(f"within {THROMBOLYSIS_WINDOW_MIN // 60}.5 h thrombolysis window")
    if clin["cpss"] >= 2:
        rationale.append(f"CPSS={clin['cpss']} (clinically positive)")
    if not rationale:
        rationale.append("no positive FAST signs")

    return {
        "p_stroke": round(p_stroke, 3),
        "severity": severity,
        "action": action,
        "time_critical": time_critical,
        "rationale": rationale,
        "clinical": clin,
        "signs": signs,
        "inability_count": inability,
        "policy": policy,
    }