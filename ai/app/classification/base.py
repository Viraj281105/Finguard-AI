"""The classification interface.

A classifier maps one transaction to one :class:`Classification`. The baseline is the deterministic
:class:`~app.classification.engine.RuleBasedClassifier`; a trained model can implement the same
``Classifier`` protocol and be combined with it through :class:`ClassifierChain` (rules first, the
model only for what the rules abstain on) without touching the API layer.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum
from typing import Protocol

from app.classification.taxonomy import Category, TxType


class Method(StrEnum):
    RULE = "rule"
    MODEL = "model"
    NONE = "none"
    """The classifier abstained: no category is asserted."""


@dataclass(frozen=True)
class ClassificationInput:
    id: str
    description: str
    tx_type: TxType | None = None
    amount: Decimal | None = None
    """Accepted for forward compatibility; the rule baseline does not use the amount."""


@dataclass(frozen=True)
class Classification:
    category: Category
    confidence: float
    """Rule strength tier (rules) or model score. Only a probability when ``confidence_calibrated``."""
    confidence_calibrated: bool
    method: Method
    version: str
    needs_review: bool
    reason: str
    rule_id: str | None = None
    alternatives: tuple[Category, ...] = ()

    @property
    def abstained(self) -> bool:
        return self.method is Method.NONE


def abstain(version: str, reason: str, alternatives: Sequence[Category] = ()) -> Classification:
    """Declines to categorise. Confidence is 0 so nothing downstream can mistake it for a decision."""
    return Classification(
        category=Category.UNCATEGORIZED,
        confidence=0.0,
        confidence_calibrated=False,
        method=Method.NONE,
        version=version,
        needs_review=True,
        reason=reason,
        alternatives=tuple(alternatives),
    )


class Classifier(Protocol):
    name: str
    version: str

    def classify(self, item: ClassificationInput) -> Classification: ...


class ClassifierChain:
    """Runs classifiers in order and returns the first answer that is not an abstention."""

    def __init__(self, classifiers: Sequence[Classifier]) -> None:
        if not classifiers:
            raise ValueError("a classifier chain needs at least one classifier")
        self._classifiers = tuple(classifiers)
        self.name = "+".join(c.name for c in self._classifiers)
        self.version = "+".join(c.version for c in self._classifiers)

    @property
    def classifiers(self) -> tuple[Classifier, ...]:
        return self._classifiers

    def classify(self, item: ClassificationInput) -> Classification:
        first_abstention: Classification | None = None
        for classifier in self._classifiers:
            result = classifier.classify(item)
            if not result.abstained:
                return result
            first_abstention = first_abstention or result
        assert first_abstention is not None
        return first_abstention
