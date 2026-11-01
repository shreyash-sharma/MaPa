"""Mandatory serving policy around neural scores, independent of model weights."""


def latch_alerts(triggers,eligible):
    """One alert state per uninterrupted, healthy, observed quiet spell."""
    output=[];latched=False
    for trigger,allowed in zip(triggers,eligible):
        if not allowed:latched=False
        elif trigger:latched=True
        output.append(latched)
    return output


def classify(probability,threshold,*,present,phone_on,usage_ok,network_ok,observed,
             heartbeat_age_bins=0,slot=48,life_tag=False,interactions=0):
    if (not all([present,phone_on,usage_ok,network_ok,observed])) or heartbeat_age_bins<0 or heartbeat_age_bins>1 or interactions<0:
        return 'MONITORING_UNAVAILABLE'
    if interactions>0:return 'ACTIVE'
    if life_tag or slot<28 or slot>=88:return 'QUIET'
    return 'UNUSUAL_INACTIVITY' if probability>=threshold else 'QUIET'
