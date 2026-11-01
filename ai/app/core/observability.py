"""W3C Trace-Context propagation and Prometheus metrics exposition for the AI service (S1-01).

Never logs or records transaction narration text (`description`).
"""

from __future__ import annotations

import logging
import re
import secrets
import threading
import time
import uuid
from dataclasses import dataclass, field

from fastapi import APIRouter, Request, Response
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint

logger = logging.getLogger(__name__)

TRACEPARENT_HEADER = "traceparent"
REQUEST_ID_HEADER = "X-Request-Id"

_TRACEPARENT_RE = re.compile(r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$")
_ALL_ZERO_TRACE = "0" * 32
_ALL_ZERO_SPAN = "0" * 16
_SAFE_REQ_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

router = APIRouter()


def parse_traceparent(header: str | None) -> tuple[str, str] | None:
    if not header:
        return None
    match = _TRACEPARENT_RE.match(header.strip().lower())
    if not match:
        return None
    trace_id, span_id, _ = match.groups()
    if trace_id == _ALL_ZERO_TRACE or span_id == _ALL_ZERO_SPAN:
        return None
    return trace_id, span_id


def sanitize_request_id(header: str | None) -> str:
    if header and _SAFE_REQ_ID_RE.match(header.strip()):
        return header.strip()
    return str(uuid.uuid4())


@dataclass
class AiMetricsRegistry:
    """Thread-safe in-memory Prometheus counters and latency summary for the AI service."""

    _lock: threading.Lock = field(default_factory=threading.Lock)
    http_requests_total: dict[tuple[str, str, int], int] = field(default_factory=dict)
    classify_batches_total: int = 0
    classify_items_total: dict[str, int] = field(
        default_factory=lambda: {"rule": 0, "model": 0, "none": 0}
    )
    classify_duration_seconds_sum: float = 0.0
    classify_duration_seconds_count: int = 0

    def record_http(self, method: str, path: str, status_code: int) -> None:
        key = (method, path, status_code)
        with self._lock:
            self.http_requests_total[key] = self.http_requests_total.get(key, 0) + 1

    def record_classify(
        self, duration_seconds: float, method_counts: dict[str, int]
    ) -> None:
        with self._lock:
            self.classify_batches_total += 1
            self.classify_duration_seconds_count += 1
            self.classify_duration_seconds_sum += max(0.0, duration_seconds)
            for method, count in method_counts.items():
                self.classify_items_total[method] = (
                    self.classify_items_total.get(method, 0) + count
                )

    def render_prometheus(self) -> str:
        with self._lock:
            lines = [
                "# HELP finguard_ai_classify_batches_total Total classification batch calls processed.",
                "# TYPE finguard_ai_classify_batches_total counter",
                f"finguard_ai_classify_batches_total {self.classify_batches_total}",
                "# HELP finguard_ai_classify_items_total Total transaction items classified by method.",
                "# TYPE finguard_ai_classify_items_total counter",
            ]
            for method, count in sorted(self.classify_items_total.items()):
                lines.append(
                    f'finguard_ai_classify_items_total{{method="{method}"}} {count}'
                )
            lines.extend(
                [
                    "# HELP finguard_ai_classify_duration_seconds Time spent classifying batches.",
                    "# TYPE finguard_ai_classify_duration_seconds summary",
                    f"finguard_ai_classify_duration_seconds_sum {self.classify_duration_seconds_sum:.6f}",
                    f"finguard_ai_classify_duration_seconds_count {self.classify_duration_seconds_count}",
                    "# HELP finguard_ai_http_requests_total Total HTTP requests handled by the AI service.",
                    "# TYPE finguard_ai_http_requests_total counter",
                ]
            )
            for (method, path, status), count in sorted(self.http_requests_total.items()):
                lines.append(
                    f'finguard_ai_http_requests_total{{method="{method}",path="{path}",status="{status}"}} {count}'
                )
            return "\n".join(lines) + "\n"


class TraceAndMetricsMiddleware(BaseHTTPMiddleware):
    """Extracts W3C traceparent + X-Request-Id, echoes correlation headers, and records HTTP metrics."""

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        parsed = parse_traceparent(request.headers.get(TRACEPARENT_HEADER))
        trace_id = parsed[0] if parsed else secrets.token_hex(16)
        span_id = secrets.token_hex(8)
        request_id = sanitize_request_id(request.headers.get(REQUEST_ID_HEADER))

        request.state.trace_id = trace_id
        request.state.span_id = span_id
        request.state.request_id = request_id

        started = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = (time.perf_counter() - started) * 1000.0

        response.headers[TRACEPARENT_HEADER] = f"00-{trace_id}-{span_id}-01"
        response.headers[REQUEST_ID_HEADER] = request_id

        metrics: AiMetricsRegistry | None = getattr(request.app.state, "metrics", None)
        if metrics is not None:
            metrics.record_http(request.method, request.url.path, response.status_code)

        logger.debug(
            "http request method=%s path=%s status=%d duration_ms=%.1f trace_id=%s span_id=%s request_id=%s",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
            trace_id,
            span_id,
            request_id,
        )
        return response


@router.get("/metrics", include_in_schema=False)
def metrics_endpoint(request: Request) -> Response:
    registry: AiMetricsRegistry = request.app.state.metrics
    return Response(
        content=registry.render_prometheus(),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )
