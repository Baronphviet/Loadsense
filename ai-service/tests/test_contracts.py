import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import HTTPException
from pydantic import ValidationError
from app.main import app, parse_prompt, generate_mockdata, analyze_log, health
from app.schemas import ParsePromptRequest, GenerateMockDataRequest, AnalyzeLogRequest, ProposedTestConfig

class ContractsTest(unittest.TestCase):
    def test_deferred_routes_never_return_fake_ai_results(self):
        calls = [
            (parse_prompt, ParsePromptRequest(prompt="Test API")),
            (generate_mockdata, GenerateMockDataRequest(schemaJson={"type": "object"}, count=2)),
            (analyze_log, AnalyzeLogRequest(totalRequests=10, failedRequests=1, throughput=10, avgLatencyMs=5, p50LatencyMs=5, p95LatencyMs=10, errorRate=10)),
        ]
        for route, request in calls:
            with self.assertRaises(HTTPException) as error:
                route(request)
            self.assertEqual(error.exception.status_code, 501)
            self.assertEqual(error.exception.detail["code"], "AI_DEFERRED")
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
