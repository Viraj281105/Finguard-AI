"""Deterministic, explainable baseline classifier built on the rule table."""

from collections.abc import Callable, Sequence

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

# Post-initial-holdout rule ids added in rules-1.0.0 (documented in evaluation/report.md).
# Kept so `rules-0.9.0` can be instantiated for versioned rollback drills.
_POST_0_9_0_PATTERNS = frozenset({
    "hypercity",
    "chai point",
    "haldirams",
    "byjus",
    "medibuddy",
})


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

    def __init__(self, rules: Sequence[Rule] = RULES, version: str = RULESET_VERSION) -> None:
        self._rules = tuple(rules)
        self.version = version

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


def _build_rules_0_9_0() -> Classifier:
    filtered = [
        r for r in RULES
        if not any(tok in r.id for tok in _POST_0_9_0_PATTERNS)
    ]
    return RuleBasedClassifier(rules=filtered, version="rules-0.9.0")


_CLASSIFIER_REGISTRY: dict[str, Callable[[], Classifier]] = {
    "rules": lambda: RuleBasedClassifier(RULES, RULESET_VERSION),
    RULESET_VERSION: lambda: RuleBasedClassifier(RULES, RULESET_VERSION),
    "rules-0.9.0": _build_rules_0_9_0,
}


def register_classifier_version(version_id: str, factory: Callable[[], Classifier]) -> None:
    """Registers a named classifier stage for chain resolution and rollback testing."""
    _CLASSIFIER_REGISTRY[version_id] = factory


def resolve_classifier_chain(chain_spec: str | None = None) -> ClassifierChain:
    """Builds a :class:`ClassifierChain` from a ``+`` or ``,`` separated chain specification."""
    spec = (chain_spec or RULESET_VERSION).strip()
    parts = [p.strip() for p in spec.replace(",", "+").split("+") if p.strip()]
    if not parts:
        parts = [RULESET_VERSION]
    stages: list[Classifier] = []
    for part in parts:
        factory = _CLASSIFIER_REGISTRY.get(part)
        if factory is None:
            known = ", ".join(sorted(_CLASSIFIER_REGISTRY))
            raise ValueError(f"Unknown classifier version '{part}' in chain '{spec}' (known: {known})")
        stages.append(factory())
    return ClassifierChain(stages)


def build_default_classifier(chain_spec: str | None = None) -> Classifier:
    """The classifier the service runs. Supports versioned chain rollback via ``chain_spec``."""
    return resolve_classifier_chain(chain_spec)
