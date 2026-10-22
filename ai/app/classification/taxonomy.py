"""Transaction category taxonomy.

The identifiers here must match ``contracts/categories.json`` (single source of truth shared with the
Java backend, the database CHECK constraint and the TypeScript frontend); ``tests/test_taxonomy.py``
fails if they drift apart.
"""

from dataclasses import dataclass
from enum import StrEnum


class Category(StrEnum):
    INCOME = "INCOME"
    GROCERIES = "GROCERIES"
    DINING = "DINING"
    TRANSPORT = "TRANSPORT"
    HOUSING = "HOUSING"
    UTILITIES = "UTILITIES"
    HEALTHCARE = "HEALTHCARE"
    INSURANCE = "INSURANCE"
    SHOPPING = "SHOPPING"
    SUBSCRIPTIONS = "SUBSCRIPTIONS"
    EDUCATION = "EDUCATION"
    ENTERTAINMENT = "ENTERTAINMENT"
    FEES = "FEES"
    LOAN_PAYMENTS = "LOAN_PAYMENTS"
    INVESTMENTS = "INVESTMENTS"
    TRANSFERS = "TRANSFERS"
    CARD_PAYMENTS = "CARD_PAYMENTS"
    REFUNDS = "REFUNDS"
    UNCATEGORIZED = "UNCATEGORIZED"


class Flow(StrEnum):
    INCOME = "INCOME"
    EXPENSE = "EXPENSE"
    NON_SPENDING = "NON_SPENDING"
    REFUND = "REFUND"
    UNKNOWN = "UNKNOWN"


class TxType(StrEnum):
    """Transaction direction, using the backend's vocabulary (``Transaction.TransactionType``)."""

    INCOME = "INCOME"
    EXPENSE = "EXPENSE"


@dataclass(frozen=True)
class CategoryInfo:
    id: Category
    label: str
    flow: Flow
    types: frozenset[TxType]


_E = frozenset({TxType.EXPENSE})
_I = frozenset({TxType.INCOME})
_BOTH = frozenset({TxType.INCOME, TxType.EXPENSE})

TAXONOMY: dict[Category, CategoryInfo] = {
    info.id: info
    for info in (
        CategoryInfo(Category.INCOME, "Income", Flow.INCOME, _I),
        CategoryInfo(Category.GROCERIES, "Groceries", Flow.EXPENSE, _E),
        CategoryInfo(Category.DINING, "Dining", Flow.EXPENSE, _E),
        CategoryInfo(Category.TRANSPORT, "Transport", Flow.EXPENSE, _E),
        CategoryInfo(Category.HOUSING, "Housing", Flow.EXPENSE, _E),
        CategoryInfo(Category.UTILITIES, "Utilities", Flow.EXPENSE, _E),
        CategoryInfo(Category.HEALTHCARE, "Healthcare", Flow.EXPENSE, _E),
        CategoryInfo(Category.INSURANCE, "Insurance", Flow.EXPENSE, _E),
        CategoryInfo(Category.SHOPPING, "Shopping", Flow.EXPENSE, _E),
        CategoryInfo(Category.SUBSCRIPTIONS, "Subscriptions", Flow.EXPENSE, _E),
        CategoryInfo(Category.EDUCATION, "Education", Flow.EXPENSE, _E),
        CategoryInfo(Category.ENTERTAINMENT, "Entertainment", Flow.EXPENSE, _E),
        CategoryInfo(Category.FEES, "Fees & charges", Flow.EXPENSE, _E),
        CategoryInfo(Category.LOAN_PAYMENTS, "Loan payments", Flow.EXPENSE, _E),
        CategoryInfo(Category.INVESTMENTS, "Investments", Flow.NON_SPENDING, _BOTH),
        CategoryInfo(Category.TRANSFERS, "Transfers", Flow.NON_SPENDING, _BOTH),
        CategoryInfo(Category.CARD_PAYMENTS, "Credit card payments", Flow.NON_SPENDING, _E),
        CategoryInfo(Category.REFUNDS, "Refunds & reversals", Flow.REFUND, _I),
        CategoryInfo(Category.UNCATEGORIZED, "Uncategorized", Flow.UNKNOWN, _BOTH),
    )
}


def allows(category: Category, tx_type: TxType | None) -> bool:
    """Whether ``category`` may be assigned to a transaction of direction ``tx_type``."""
    return tx_type is None or tx_type in TAXONOMY[category].types
