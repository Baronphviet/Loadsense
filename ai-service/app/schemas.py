"""Contracts only. Local model invocation is intentionally deferred."""
from typing import Any, Literal
from pydantic import BaseModel, Field, ConfigDict

class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")

class ParsePromptRequest(Contract):
    prompt: str = Field(min_length=1, max_length=4000)

class ProposedTestConfig(Contract):
    name: str
    targetUrl: str
    httpMethod: Literal["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]
    headers: dict[str, str] = Field(default_factory=dict)
    body: str | None = None
    virtualUsers: int = Field(ge=1, le=300)
    durationSeconds: int = Field(ge=1, le=600)
    requestsPerSecond: int = Field(ge=1, le=500)
    timeoutMs: int = Field(ge=100, le=30000)

class ParsePromptResponse(Contract):
    config: ProposedTestConfig
    warnings: list[str] = Field(default_factory=list)
    requiresApproval: bool = True

class GenerateMockDataRequest(Contract):
    schemaJson: dict[str, Any]
    count: int = Field(ge=1, le=1000)

class GenerateMockDataResponse(Contract):
    payloads: list[dict[str, Any]]

class AnalyzeLogRequest(Contract):
    # Only aggregated metrics, never raw headers, tokens or bodies.
    totalRequests: int = Field(ge=0)
    failedRequests: int = Field(ge=0)
    throughput: float = Field(ge=0)
    avgLatencyMs: float = Field(ge=0)
    p50LatencyMs: float = Field(ge=0)
    p95LatencyMs: float = Field(ge=0)
    errorRate: float = Field(ge=0, le=100)

class AnalyzeLogResponse(Contract):
    summary: str
    bottlenecks: list[str]
    recommendations: list[str]
