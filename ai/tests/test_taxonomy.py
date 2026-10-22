import json
from pathlib import Path

from app.classification.taxonomy import TAXONOMY, Category, Flow, TxType

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "categories.json"


def test_taxonomy_matches_the_shared_contract():
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))["categories"]

    assert [c["id"] for c in contract] == [c.value for c in TAXONOMY]
    for entry in contract:
        info = TAXONOMY[Category(entry["id"])]
        assert info.label == entry["label"]
        assert info.flow.value == entry["flow"]
        assert {t.value for t in info.types} == set(entry["types"])


def test_money_movements_are_never_ordinary_expenses():
    for category in (Category.TRANSFERS, Category.CARD_PAYMENTS, Category.INVESTMENTS):
        assert TAXONOMY[category].flow is Flow.NON_SPENDING
    assert TAXONOMY[Category.REFUNDS].flow is Flow.REFUND
    assert TAXONOMY[Category.INCOME].types == {TxType.INCOME}
    assert TAXONOMY[Category.REFUNDS].types == {TxType.INCOME}
    assert TAXONOMY[Category.CARD_PAYMENTS].types == {TxType.EXPENSE}
