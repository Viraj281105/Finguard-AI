"""Normalisation of free-text bank narrations into a form rules can match reliably.

Bank narrations look like ``UPI-SWIGGY-swiggy@icici-ICIC0000123-412345678901-Payment``,
``NEFT-ACME LTD-SALARY MARCH`` or ``POS 4567XXXXXX1234 AMAZON PAY INDIA``. Normalisation lowercases,
folds accents, drops reference numbers, IFSC codes and UPI handle suffixes (``@okhdfcbank``), and
collapses every separator into a single space, so ``Swiggy*Order``, ``SWIGGY_ORDER`` and
``swiggy order`` all become ``swiggy order``.
"""

import re
import unicodedata
from dataclasses import dataclass

_VPA = re.compile(r"([a-z0-9._-]+)@[a-z0-9]+")
_IFSC = re.compile(r"\b[a-z]{4}0[a-z0-9]{6}\b")
_NON_ALNUM = re.compile(r"[^a-z0-9]+")
_CHANNELS = ("upi", "neft", "imps", "rtgs", "nach", "ach", "ecs", "pos", "atm", "chq", "cheque")


@dataclass(frozen=True)
class NormalizedText:
    text: str
    """Lowercase, separator-collapsed text with reference numbers removed."""

    channel: str | None
    """First payment channel found (``upi``, ``neft``, ``atm`` ...), if any."""

    @property
    def is_empty(self) -> bool:
        return not self.text


def normalize(description: str | None) -> NormalizedText:
    if not description:
        return NormalizedText("", None)

    folded = unicodedata.normalize("NFKD", description)
    lowered = "".join(ch for ch in folded if not unicodedata.combining(ch)).lower()
    # Keep the payee part of a UPI address (swiggy@icici -> swiggy), drop the bank handle.
    lowered = _VPA.sub(r"\1", lowered)
    lowered = _IFSC.sub(" ", lowered)

    tokens = [t for t in _NON_ALNUM.split(lowered) if t]
    # Reference numbers, masked card/account numbers and dates carry no category signal.
    kept = [t for t in tokens if sum(c.isdigit() for c in t) < 5 and not _is_mask(t)]
    text = " ".join(kept)

    channel = next((c for c in _CHANNELS if c in kept), None)
    return NormalizedText(text, channel)


def _is_mask(token: str) -> bool:
    """Masked card numbers such as ``4567xxxxxx1234`` after separators were split."""
    return len(token) >= 6 and set(token) <= set("x0123456789") and "x" in token
