r"""Regenerates ``contracts/classify-examples.json``: a recorded request/response pair for
``POST /v1/classify`` that the Java client tests replay offline and the Python tests verify against
the live classifier, so the two sides cannot drift apart silently.

    cd ai; .\.venv\Scripts\python -m evaluation.export_examples
"""

import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import create_app

OUTPUT = Path(__file__).resolve().parents[2] / "contracts" / "classify-examples.json"

REQUEST = {
    "transactions": [
        {"id": "tx-groceries", "description": "UPI-BIGBASKET-bigbasket@hdfcbank-412345678901",
         "type": "EXPENSE", "amount": 1250.5},
        {"id": "tx-salary", "description": "NEFT-ACME LTD-SALARY MARCH", "type": "INCOME",
         "amount": 75000},
        {"id": "tx-card-bill", "description": "HDFC CREDIT CARD BILL PAYMENT", "type": "EXPENSE",
         "amount": 18000},
        {"id": "tx-refund", "description": "REFUND FROM AMAZON ORDER", "type": "INCOME", "amount": 499},
        {"id": "tx-weak-transfer", "description": "NEFT-SOME PERSON-NEFT", "type": "EXPENSE",
         "amount": 2000},
        {"id": "tx-unknown-upi", "description": "UPI-RAMESH KUMAR-ramesh@oksbi-412345678901",
         "type": "EXPENSE", "amount": 300},
        {"id": "tx-credit-from-merchant", "description": "UPI-SWIGGY-swiggy@icici", "type": "INCOME",
         "amount": 220},
        {"id": "tx-atm", "description": "ATM WDL MG ROAD", "type": "EXPENSE", "amount": 10000},
    ]
}


def main() -> None:
    client = TestClient(create_app(Settings()))
    response = client.post("/v1/classify", json=REQUEST)
    response.raise_for_status()
    OUTPUT.write_text(
        json.dumps({"request": REQUEST, "response": response.json()}, indent=2) + "\n", encoding="utf-8"
    )
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    main()
