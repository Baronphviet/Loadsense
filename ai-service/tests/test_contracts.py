import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import HTTPException
from pydantic import ValidationError
from app.main import app, parse_prompt, generate_mockdata, analyze_log, health
from app.schemas import ParsePromptRequest, GenerateMockDataRequest, AnalyzeLogRequest, ProposedTestConfig

SAMPLE = {"elapsedSeconds": 1, "totalRequests": 1, "failedRequests": 0, "throughput": 1, "avgLatencyMs": 1, "p50LatencyMs": 1, "p95LatencyMs": 1, "errorRate": 0, "activeUsers": 1}
SAMPLES = [dict(SAMPLE, elapsedSeconds=i, totalRequests=i * 3) for i in (1, 2, 3)]

class ContractsTest(unittest.TestCase):
    def test_unconfigured_routes_never_return_fake_ai_results(self):
        calls = [
            (parse_prompt, ParsePromptRequest(prompt="Test API")),
            (generate_mockdata, GenerateMockDataRequest(schemaJson={"type": "object"}, count=2)),
            (analyze_log, AnalyzeLogRequest(totalRequests=10, failedRequests=1, throughput=10, avgLatencyMs=5, p50LatencyMs=5, p95LatencyMs=10, errorRate=10, samples=SAMPLES)),
        ]
        for route, request in calls:
            with self.assertRaises(HTTPException) as error:
                route(request)
            self.assertIn(error.exception.status_code, (400, 503))
        self.assertFalse(health()["aiEnabled"])
    def test_schema_has_endpoints_and_bounded_load(self):
        schema = app.openapi()
        self.assertTrue({"/parse-prompt", "/generate-mockdata", "/analyze-log"}.issubset(schema["paths"]))
        with self.assertRaises(ValidationError):
            ProposedTestConfig(name="Test", targetUrl="http://localhost", httpMethod="GET", virtualUsers=301,
                               durationSeconds=10, requestsPerSecond=100, timeoutMs=1000)
        with self.assertRaises(ValidationError):
            GenerateMockDataRequest(schemaJson={}, count=1001)
        with self.assertRaises(ValidationError):
            ParsePromptRequest(prompt="Test", unrecognized="value")

if __name__ == "__main__":
    unittest.main()
