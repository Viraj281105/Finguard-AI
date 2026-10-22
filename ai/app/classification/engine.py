"""Deterministic, explainable baseline classifier built on the rule table."""

from collections.abc import Sequence

from app.classification.base import (
    Classification,
    ClassificationInput,
    Classifier,
    ClassifierChain,
    Method,
    abstain,
)
from app.classification.normalize import normalize
from app.classification.rules import P_CHANNEL, RULES, RULESET_VERSION, WEAK, Rule
from app.classification.taxonomy import Category, TxType, allows

_MAX_ALTERNATIVES = 3


class RuleBasedClassifier:
    """Matches normalised narrations against the rule table.

    Decision procedure (same input -> same output; no randomness, no I/O):

    1. Collect every rule that matches. Drop rules whose category does not fit the transaction's
       direction (``taxonomy.allows``): ``SALARY`` on a debit, or ``SWIGGY`` on a credit, is not
       evidence for an ordinary category.
    2. If the strongest direction-incompatible match outranks everything compatible (a credit that
       names a restaurant is probably a refund, not a transfer), abstain.
    3. Otherwise the highest-priority, then strongest rule wins. Two different categories at the
       same priority and strength are an ambiguity: abstain and list the candidates.
    4. A result is flagged ``needs_review`` when it rests on a WEAK rule or when rules for another
       category (other than bare channel hints such as NEFT) also matched.
    """

    name = "rules"
    version = RULESET_VERSION

    def __init__(self, rules: Sequence[Rule] = RULES) -> None:
        self._rules = tuple(rules)

    def classify(self, item: ClassificationInput) -> Classification:
        norm = normalize(item.description)
        if norm.is_empty:
            return abstain(self.version, "The description has no usable text")

        matches = [rule for rule in self._rules if rule.pattern.search(norm.text)]
        compatible = [
            r for r in matches
            if r.category is Category.UNCATEGORIZED or allows(r.category, item.tx_type)
        ]
        incompatible = [r for r in matches if r not in compatible]

        if not compatible and not incompatible:
            if norm.channel == "upi":
                return abstain(self.version, "UPI payee is not a recognised merchant")
            return abstain(self.version, "No rule matches this description")

        best_incompatible = max(incompatible, key=_rank, default=None)
        best = max(compatible, key=_rank, default=None)
        if best_incompatible is not None and (best is None or _rank(best_incompatible)[0] > _rank(best)[0]):
            return self._direction_conflict(best_incompatible, item.tx_type)
        assert best is not None

        top = [r for r in compatible if _rank(r) == _rank(best)]
        top_categories = sorted({r.category for r in top}, key=str)
        if len(top_categories) > 1:
            return abstain(
                self.version,
                "Ambiguous: rules for several categories match with equal weight",
                _alternatives(top_categories),
            )

        if best.category is Category.UNCATEGORIZED:
            return abstain(self.version, best.reason or "Explicitly not categorisable")

        # Channel hints (a bare NEFT/IMPS) say how money moved, not why: they are not competing evidence.
        competing = sorted(
            {
                r.category for r in compatible
                if r.priority > P_CHANNEL and r.category not in (best.category, Category.UNCATEGORIZED)
            },
            key=str,
        )
        return Classification(
            category=best.category,
            confidence=best.strength,
            confidence_calibrated=False,
            method=Method.RULE,
            version=self.version,
            needs_review=best.strength <= WEAK or bool(competing),
            reason=f"Matched rule {best.id}",
            rule_id=best.id,
            alternatives=_alternatives(competing),
        )

    def _direction_conflict(self, rule: Rule, tx_type: TxType | None) -> Classification:
        direction = "credit" if tx_type is TxType.INCOME else "debit"
        alternatives: list[Category] = []
        if tx_type is TxType.INCOME:
            alternatives.append(Category.REFUNDS)  # money coming back from a merchant
        return abstain(
            self.version,
            f"Matched {rule.id} but a {direction} is unusual for {rule.category.value}",
            alternatives,
        )


def _rank(rule: Rule) -> tuple[int, float]:
    return (rule.priority, rule.strength)


def _alternatives(categories: Sequence[Category]) -> list[Category]:
    return list(categories)[:_MAX_ALTERNATIVES]


def build_default_classifier() -> Classifier:
    """The classifier the service runs. Add a trained model by appending it to the chain."""
    return ClassifierChain([RuleBasedClassifier()])
