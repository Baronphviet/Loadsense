import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import HTTPException
from pydantic import ValidationError
from app.main import app, parse_prompt, generate_mockdata, analyze_log, explain_suite_case, health
from app.schemas import (ParsePromptRequest, GenerateMockDataRequest, AnalyzeLogRequest,
                         ExplainSuiteCaseRequest, ProposedTestConfig)

SAMPLE = {"elapsedSeconds": 1, "totalRequests": 1, "failedRequests": 0, "throughput": 1, "avgLatencyMs": 1, "p50LatencyMs": 1, "p95LatencyMs": 1, "errorRate": 0, "activeUsers": 1}
SAMPLES = [dict(SAMPLE, elapsedSeconds=i, totalRequests=i * 3) for i in (1, 2, 3)]

class ContractsTest(unittest.TestCase):
    def test_unconfigured_routes_never_return_fake_ai_results(self):
        calls = [
            (parse_prompt, ParsePromptRequest(prompt="Test API")),
            (generate_mockdata, GenerateMockDataRequest(schemaJson={"type": "object"}, count=2)),
            (analyze_log, AnalyzeLogRequest(totalRequests=10, failedRequests=1, throughput=10, avgLatencyMs=5, p50LatencyMs=5, p95LatencyMs=10, errorRate=10, samples=SAMPLES)),
            (explain_suite_case, ExplainSuiteCaseRequest(caseId="HOM-001", name="Home", httpMethod="GET", targetUrl="https://example.com", status="FAILED", httpStatus=500)),
        ]
        for route, request in calls:
            with self.assertRaises(HTTPException) as error:
                route(request)
            self.assertIn(error.exception.status_code, (400, 503))
        self.assertFalse(health()["aiEnabled"])
    def test_schema_has_endpoints_and_bounded_load(self):
        schema = app.openapi()
        self.assertTrue({"/parse-prompt", "/generate-mockdata", "/analyze-log", "/explain-suite-case"}.issubset(schema["paths"]))
        with self.assertRaises(ValidationError):
            ProposedTestConfig(name="Test", targetUrl="http://localhost", httpMethod="GET", virtualUsers=301,
                               durationSeconds=10, requestsPerSecond=100, timeoutMs=1000)
        with self.assertRaises(ValidationError):
            GenerateMockDataRequest(schemaJson={}, count=1001)
        with self.assertRaises(ValidationError):
            ParsePromptRequest(prompt="Test", unrecognized="value")
        req = AnalyzeLogRequest(
            totalRequests=12, failedRequests=2, throughput=4, avgLatencyMs=10, p50LatencyMs=8, p95LatencyMs=20,
            errorRate=16.7, samples=SAMPLES, errorBreakdown={"http5xx": 2}, anomalyWarning="High error rate"
        )
        self.assertEqual(req.errorBreakdown, {"http5xx": 2})
        self.assertEqual(req.anomalyWarning, "High error rate")

if __name__ == "__main__":
    unittest.main()
