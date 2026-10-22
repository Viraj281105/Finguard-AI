"""Request/response schemas of ``POST /v1/classify`` (the contract is documented in docs/API.md)."""

from decimal import Decimal
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.classification.taxonomy import Category, TxType

MAX_BATCH = 500
MAX_DESCRIPTION = 1000


class ClassifyItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: Annotated[str, Field(min_length=1, max_length=64)]
    description: Annotated[str, Field(max_length=MAX_DESCRIPTION)]
    type: TxType | None = Field(
        default=None, description="Direction of the transaction: INCOME (credit) or EXPENSE (debit)."
    )
    amount: Decimal | None = Field(
        default=None, ge=0, allow_inf_nan=False,
        description="Positive amount. Accepted for forward compatibility; the rule baseline ignores it.",
    )

    @field_validator("id")
    @classmethod
    def _id_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("id must not be blank")
        return value


class ClassifyRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    transactions: Annotated[list[ClassifyItem], Field(max_length=MAX_BATCH)]

    @model_validator(mode="after")
    def _ids_unique(self) -> "ClassifyRequest":
        ids = [item.id for item in self.transactions]
        if len(ids) != len(set(ids)):
            raise ValueError("transaction ids must be unique within a request")
        return self


class ClassifyResult(BaseModel):
    id: str
    category: Category = Field(description="Taxonomy id; UNCATEGORIZED when the classifier abstains.")
    confidence: float = Field(
        ge=0, le=1,
        description="Rule strength tier or model score. A probability ONLY if confidence_calibrated.",
    )
    confidence_calibrated: bool
    method: str = Field(description="'rule', 'model', or 'none' when the classifier abstained.")
    model_version: str = Field(description="Version of the rule set / model that produced the result.")
    rule_id: str | None = None
    needs_review: bool
    reason: str
    alternatives: list[Category] = Field(default_factory=list)


class ClassifyResponse(BaseModel):
    model_version: str
    results: list[ClassifyResult]
