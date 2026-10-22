"""Behaviour of the rule baseline. The descriptions are synthetic, written for these tests."""

import pytest

from app.classification.base import (
    Classification,
    ClassificationInput,
    ClassifierChain,
    Method,
    abstain,
)
from app.classification.engine import RuleBasedClassifier
from app.classification.rules import RULES, RULESET_VERSION
from app.classification.taxonomy import TAXONOMY, Category, TxType

DEBIT, CREDIT = TxType.EXPENSE, TxType.INCOME
classifier = RuleBasedClassifier()


def run(description: str, tx_type: TxType | None = DEBIT) -> Classification:
    return classifier.classify(ClassificationInput(id="x", description=description, tx_type=tx_type))


# (description, direction, expected category) - at least two per supported category.
SUPPORTED = [
    ("NEFT-ACME LTD-SALARY MARCH", CREDIT, Category.INCOME),
    ("SAL CREDIT INFOSYS LTD", CREDIT, Category.INCOME),
    ("INT.PD:01-03-2026 TO 31-03-2026", CREDIT, Category.INCOME),
    ("UPI-BIGBASKET-bigbasket@hdfcbank-HDFC0000001-412345678901", DEBIT, Category.GROCERIES),
    ("POS 4567XXXXXX1234 DMART AVENUE SUPERMARTS", DEBIT, Category.GROCERIES),
    ("UPI-SWIGGY INSTAMART-swiggy@icici", DEBIT, Category.GROCERIES),
    ("UPI-ZOMATO-zomato@hdfcbank-order", DEBIT, Category.DINING),
    ("POS MCDONALD'S BENGALURU", DEBIT, Category.DINING),
    ("UPI-STARBUCKS COFFEE-starbucks@okaxis", DEBIT, Category.DINING),
    ("UPI-UBER INDIA-uber@axisbank", DEBIT, Category.TRANSPORT),
    ("IRCTC TICKET BOOKING", DEBIT, Category.TRANSPORT),
    ("HPCL PETROL PUMP KORAMANGALA", DEBIT, Category.TRANSPORT),
    ("UPI-NOBROKER-RENT PAYMENT", DEBIT, Category.HOUSING),
    ("HOUSE RENT MARCH", DEBIT, Category.HOUSING),
    ("BESCOM ELECTRICITY BILL", DEBIT, Category.UTILITIES),
    ("AIRTEL PREPAID RECHARGE", DEBIT, Category.UTILITIES),
    ("ACT FIBERNET BROADBAND", DEBIT, Category.UTILITIES),
    ("UPI-APOLLO PHARMACY-apollo@ybl", DEBIT, Category.HEALTHCARE),
    ("CITY HOSPITAL OPD BILL", DEBIT, Category.HEALTHCARE),
    ("UPI-PHARMEASY-pharmeasy@icici", DEBIT, Category.HEALTHCARE),
    ("HDFC LIFE INSURANCE PREMIUM", DEBIT, Category.INSURANCE),
    ("STAR HEALTH INSURANCE", DEBIT, Category.INSURANCE),
    ("AMAZON SELLER SERVICES AMAZON", DEBIT, Category.SHOPPING),
    ("UPI-MYNTRA-myntra@axisbank", DEBIT, Category.SHOPPING),
    ("POS FLIPKART INTERNET", DEBIT, Category.SHOPPING),
    ("UPI-NETFLIX-netflix@icici-subscription", DEBIT, Category.SUBSCRIPTIONS),
    ("SPOTIFY INDIA", DEBIT, Category.SUBSCRIPTIONS),
    ("AMAZON PRIME MEMBERSHIP", DEBIT, Category.SUBSCRIPTIONS),
    ("UDEMY ONLINE COURSE", DEBIT, Category.EDUCATION),
    ("SCHOOL FEES TERM 2", DEBIT, Category.EDUCATION),
    ("UPI-BOOKMYSHOW-bms@icici", DEBIT, Category.ENTERTAINMENT),
    ("PVR CINEMAS", DEBIT, Category.ENTERTAINMENT),
    ("SMS ALERT CHARGES Q1", DEBIT, Category.FEES),
    ("CREDIT CARD ANNUAL FEE", DEBIT, Category.FEES),
    ("GST ON ATM CHARGES", DEBIT, Category.FEES),
    ("ECS-HDFC BANK HOME LOAN EMI", DEBIT, Category.LOAN_PAYMENTS),
    ("BAJAJ FINANCE LTD ACH DEBIT", DEBIT, Category.LOAN_PAYMENTS),
    ("ZERODHA BROKING LTD FUNDS", DEBIT, Category.INVESTMENTS),
    ("UPI-GROWW-groww@icici-MUTUAL FUND SIP", DEBIT, Category.INVESTMENTS),
    ("REDEMPTION MUTUAL FUND CREDIT", CREDIT, Category.INVESTMENTS),
    ("FD BOOKING 0123456", DEBIT, Category.INVESTMENTS),
    ("SELF TRANSFER TO SAVINGS ACCOUNT", DEBIT, Category.TRANSFERS),
    ("NEFT-RAMESH KUMAR-NEFT TRANSFER", DEBIT, Category.TRANSFERS),
    ("IMPS-412345678901-PRIYA SHARMA", CREDIT, Category.TRANSFERS),
    ("HDFC CREDIT CARD BILL PAYMENT", DEBIT, Category.CARD_PAYMENTS),
    ("CRED CLUB CARD PAYMENT", DEBIT, Category.CARD_PAYMENTS),
    ("BILLDESK ICICI BANK CREDIT CARD", DEBIT, Category.CARD_PAYMENTS),
    ("REFUND FROM AMAZON ORDER", CREDIT, Category.REFUNDS),
    ("UPI-SWIGGY-REVERSAL", CREDIT, Category.REFUNDS),
    ("CASHBACK CREDIT", CREDIT, Category.REFUNDS),
]


@pytest.mark.parametrize(("description", "tx_type", "expected"), SUPPORTED)
def test_supported_categories(description, tx_type, expected):
    result = run(description, tx_type)

    assert result.category is expected, (result.category, result.reason)
    assert result.method is Method.RULE
    assert result.rule_id is not None
    assert result.version == RULESET_VERSION
    assert 0 < result.confidence <= 1
    assert result.confidence_calibrated is False


def test_every_assignable_category_is_exercised_by_a_case():
    covered = {expected for _, _, expected in SUPPORTED}
    assignable = set(TAXONOMY) - {Category.UNCATEGORIZED}
    assert covered == assignable


# ---- Unknown merchants and ambiguity: never invent certainty -----------------------------------

UNCERTAIN = [
    ("UPI-RAMESH KUMAR-ramesh@oksbi-HDFC0000001-412345678901", DEBIT, "UPI payee"),
    ("UPI-9876543210@ybl", DEBIT, "UPI payee"),
    ("XYZ TRADERS PVT LTD", DEBIT, "No rule"),
    ("POS 4567XXXXXX1234 JOES SHOP", DEBIT, "No rule"),
    ("ATM WDL, MG ROAD BENGALURU", DEBIT, "Cash"),
    ("CASH DEPOSIT BRANCH", CREDIT, "Cash"),
    ("BILLDESK PAYMENT", DEBIT, "gateway"),
    ("PAYTM", DEBIT, "gateway"),
    ("AMAZON PAY INDIA", DEBIT, "Wallet"),
    ("", DEBIT, "no usable text"),
    ("   ", CREDIT, "no usable text"),
    ("0012345678901", DEBIT, "no usable text"),
]


@pytest.mark.parametrize(("description", "tx_type", "reason_part"), UNCERTAIN)
def test_unknown_and_ambiguous_narrations_abstain(description, tx_type, reason_part):
    result = run(description, tx_type)

    assert result.category is Category.UNCATEGORIZED
    assert result.method is Method.NONE
    assert result.confidence == 0.0
    assert result.needs_review is True
    assert reason_part.lower() in result.reason.lower()
    assert result.rule_id is None


def test_equal_weight_rules_for_different_categories_are_an_ambiguity():
    tie = run("ZEPTO AND UBER", DEBIT)  # a grocery brand and a taxi brand, same priority/strength

    assert tie.category is Category.UNCATEGORIZED
    assert tie.method is Method.NONE
    assert set(tie.alternatives) == {Category.GROCERIES, Category.TRANSPORT}
    assert "ambiguous" in tie.reason.lower()


def test_a_more_specific_rule_wins_but_the_competing_category_is_reported():
    result = run("ZEPTO AND NETFLIX COMBINED BILL", DEBIT)

    assert result.category is Category.SUBSCRIPTIONS
    assert Category.GROCERIES in result.alternatives
    assert result.needs_review


def test_weak_evidence_is_flagged_for_review_with_the_weaker_confidence():
    weak = run("NEFT-SOME PERSON-NEFT", DEBIT)
    strong = run("UPI-ZEPTO-zepto@icici", DEBIT)

    assert weak.category is Category.TRANSFERS and weak.needs_review
    assert strong.category is Category.GROCERIES and not strong.needs_review
    assert weak.confidence < strong.confidence


def test_unspecific_keywords_do_not_beat_specific_rules():
    assert run("SWIGGY INSTAMART").category is Category.GROCERIES  # not dining
    assert run("AMAZON PRIME").category is Category.SUBSCRIPTIONS  # not shopping
    assert run("SCHOOL FEES").category is Category.EDUCATION  # not fees
    assert run("CREDIT CARD ANNUAL FEE").category is Category.FEES  # not a card payment
    assert run("EDUCATION LOAN EMI").category is Category.LOAN_PAYMENTS  # not education


def test_keywords_match_on_token_boundaries_only():
    assert run("PARENTAL SUPPORT").category is Category.UNCATEGORIZED  # 'rent' inside 'parental'
    assert run("CURRENTLY UNAVAILABLE").category is Category.UNCATEGORIZED  # 'rent' inside 'currently'
    assert run("GREENFIELD SCHOOLHOUSE").category is Category.UNCATEGORIZED  # 'school' inside a word


# ---- Direction-aware business rules: transfers, refunds, card payments, investments -------------


def test_a_credit_from_an_expense_merchant_is_not_asserted_as_anything():
    result = run("UPI-SWIGGY-swiggy@icici", CREDIT)

    assert result.category is Category.UNCATEGORIZED and result.method is Method.NONE
    assert Category.REFUNDS in result.alternatives  # probably a refund, but we do not claim it
    assert "credit" in result.reason


def test_a_debit_that_says_salary_or_refund_is_not_income_or_refund():
    for description in ("SALARY ADVANCE RECOVERY", "REFUND PROCESSING"):
        result = run(description, DEBIT)
        assert result.category not in (Category.INCOME, Category.REFUNDS)
        assert result.method is Method.NONE


def test_refund_rules_beat_the_merchant_they_mention():
    assert run("REFUND ZOMATO ORDER", CREDIT).category is Category.REFUNDS


def test_credit_card_payment_is_a_non_spending_category_not_shopping():
    assert run("ICICI BANK CREDIT CARD PAYMENT AMAZON", DEBIT).category is Category.CARD_PAYMENTS


def test_investment_movements_work_in_both_directions():
    assert run("ZERODHA FUND ADD", DEBIT).category is Category.INVESTMENTS
    assert run("ZERODHA WITHDRAWAL PAYOUT", CREDIT).category is Category.INVESTMENTS


def test_direction_is_optional_for_backward_compatibility():
    assert run("SWIGGY", None).category is Category.DINING
    assert run("SALARY", None).category is Category.INCOME


# ---- Determinism, rule hygiene, extensibility -------------------------------------------------


def test_same_input_gives_identical_output():
    cases = [(d, t) for d, t, _ in SUPPORTED] + [(d, t) for d, t, _ in UNCERTAIN]
    assert [run(d, t) for d, t in cases] == [run(d, t) for d, t in cases]


def test_rule_ids_are_unique_and_namespaced_by_category():
    ids = [r.id for r in RULES]
    assert len(ids) == len(set(ids)), sorted({i for i in ids if ids.count(i) > 1})
    assert all(r.id.startswith(r.category.value.lower() + ".") for r in RULES)


def test_no_rule_matches_empty_text():
    for rule in RULES:
        assert rule.pattern.search("") is None, rule.id


def test_a_trained_model_can_complement_the_rules_through_the_chain():
    class StubModel:
        name = "stub-model"
        version = "stub-0"

        def classify(self, item: ClassificationInput) -> Classification:
            return Classification(
                category=Category.SHOPPING, confidence=0.6, confidence_calibrated=True,
                method=Method.MODEL, version=self.version, needs_review=False, reason="model",
            )

    chain = ClassifierChain([classifier, StubModel()])
    assert chain.version == f"{RULESET_VERSION}+stub-0"

    rule_hit = chain.classify(ClassificationInput("1", "ZEPTO", DEBIT))
    assert rule_hit.method is Method.RULE and rule_hit.category is Category.GROCERIES

    fallback = chain.classify(ClassificationInput("2", "XYZ TRADERS PVT LTD", DEBIT))
    assert fallback.method is Method.MODEL and fallback.confidence_calibrated is True


def test_chain_returns_the_first_abstention_when_everyone_abstains():
    chain = ClassifierChain([classifier])
    result = chain.classify(ClassificationInput("1", "XYZ TRADERS", DEBIT))

    assert result.method is Method.NONE
    assert result == abstain(RULESET_VERSION, result.reason)


def test_empty_chain_is_rejected():
    with pytest.raises(ValueError):
        ClassifierChain([])
