"""``POST /v1/classify``: categorise a batch of transactions.

Stateless: nothing is stored and transaction descriptions are never logged (only counts and timing).
"""

import logging
import time

from fastapi import APIRouter, Depends, Request

from app.classification.base import ClassificationInput
from app.core.security import require_internal_token
from app.schemas.classify import ClassifyRequest, ClassifyResponse, ClassifyResult

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", dependencies=[Depends(require_internal_token)])


@router.post("/classify", response_model=ClassifyResponse, tags=["classification"])
def classify(body: ClassifyRequest, request: Request) -> ClassifyResponse:
    classifier = request.app.state.classifier
    started = time.perf_counter()

    results: list[ClassifyResult] = []
    for item in body.transactions:
        outcome = classifier.classify(
            ClassificationInput(
                id=item.id, description=item.description, tx_type=item.type, amount=item.amount
            )
        )
        results.append(
            ClassifyResult(
                id=item.id,
                category=outcome.category,
                confidence=outcome.confidence,
                confidence_calibrated=outcome.confidence_calibrated,
                method=outcome.method.value,
                model_version=outcome.version,
                rule_id=outcome.rule_id,
                needs_review=outcome.needs_review,
                reason=outcome.reason,
                alternatives=list(outcome.alternatives),
            )
        )

    logger.info(
        "classified %d transactions (%d abstained) in %.1f ms",
        len(results),
        sum(1 for r in results if r.method == "none"),
        (time.perf_counter() - started) * 1000,
    )
    return ClassifyResponse(model_version=classifier.version, results=results)
