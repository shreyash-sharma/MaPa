"""Provenance requirements for future episode records; does not inject episodes."""
from .bins import OBSERVATION_BASES


def validate_observation_basis(episode):
    basis = episode.get("observation_basis")
    if basis not in OBSERVATION_BASES:
        raise ValueError("Episode requires a valid observation_basis")
    if episode.get("source") == "synthetic" and basis != "synthetic":
        raise ValueError("Synthetic episodes must declare synthetic observation")
    if episode.get("source") in {"s3", "ubiqlog"} and basis != "heartbeat_inferred":
        raise ValueError("Real-source episodes require heartbeat_inferred observation")
