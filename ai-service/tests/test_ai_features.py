import unittest
from unittest.mock import patch
from app import main
from app.schemas import ParsePromptRequest, GenerateMockDataRequest, AnalyzeLogRequest
from fastapi import HTTPException
import requests
from app.model import NvidiaModel

SAMPLE = {'elapsedSeconds': 1, 'totalRequests': 1, 'failedRequests': 0, 'throughput': 1, 'avgLatencyMs': 1, 'p50LatencyMs': 1, 'p95LatencyMs': 1, 'p99LatencyMs': 0.0, 'intervalP95Ms': 0.0, 'intervalErrorRate': 0.0, 'errorRate': 0, 'activeUsers': 1}
SAMPLES = [dict(SAMPLE, elapsedSeconds=i, totalRequests=i * 4) for i in (1, 2, 3)]


class AiFeaturesTest(unittest.TestCase):
    @patch('app.main.model.generate')
    def test_prompt_returns_validated_proposal(self, generate):
        generate.return_value = {'config': {'name': 'Demo', 'targetUrl': 'http://localhost', 'httpMethod': 'GET', 'headers': {}, 'body': None, 'virtualUsers': 2, 'durationSeconds': 10, 'requestsPerSecond': 10, 'timeoutMs': 1000}, 'warnings': [], 'requiresApproval': True}
        result = main.parse_prompt(ParsePromptRequest(prompt='Kiểm thử API'))
        self.assertEqual(result.config.virtualUsers, 2)
        self.assertTrue(result.requiresApproval)

    @patch('app.main.model.generate')
    def test_mock_payloads_must_match_schema_and_count(self, generate):
        generate.return_value = {'payloads': [{'age': 'wrong'}]}
        with self.assertRaises(HTTPException):
            main.generate_mockdata(GenerateMockDataRequest(schemaJson={'type': 'object', 'properties': {'age': {'type': 'integer'}}}, count=1))
        generate.return_value = {'payloads': [{'age': 1}]}
        self.assertEqual(main.generate_mockdata(GenerateMockDataRequest(schemaJson={'type': 'object', 'properties': {'age': {'type': 'integer'}}}, count=1)).payloads, [{'age': 1}])

    @patch('app.main.model.generate')
    def test_invalid_model_result_is_error(self, generate):
        generate.return_value = {'summary': 'missing fields'}
        with self.assertRaises(HTTPException):
            main.analyze_log(AnalyzeLogRequest(totalRequests=12, failedRequests=0, throughput=1, avgLatencyMs=1, p50LatencyMs=1, p95LatencyMs=1, errorRate=0, samples=SAMPLES))

    def test_validate_saved_payload_rejects_schema_mismatch(self):
        from app.schemas import ValidateMockDataRequest
        with self.assertRaises(HTTPException):
            main.validate_mockdata(ValidateMockDataRequest(schemaJson={'type': 'object', 'required': ['id']}, payloads=[{}]))

    def test_unresolvable_schema_reference_is_an_input_error(self):
        from app.schemas import ValidateMockDataRequest
        with self.assertRaises(HTTPException) as error:
            main.validate_mockdata(ValidateMockDataRequest(schemaJson={'$ref': '#/$defs/missing'}, payloads=[{}]))
        self.assertEqual(error.exception.status_code, 400)

    @patch('app.main.model.generate', return_value={'payloads': [{}]})
    def test_generated_unresolvable_reference_is_an_input_error(self, generate):
        with self.assertRaises(HTTPException) as error:
            main.generate_mockdata(GenerateMockDataRequest(schemaJson={'$ref': '#/$defs/missing'}, count=1))
        self.assertEqual(error.exception.status_code, 400)

    @patch('app.model.time.sleep')
    @patch.dict('os.environ', {'NVIDIA_API_KEY': 'test-key'})
    @patch('app.model.requests.post')
    def test_provider_timeout_and_rate_limit(self, post, sleep):
        post.side_effect = requests.Timeout()
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_TIMEOUT')
        self.assertEqual(post.call_count, 1)
        post.reset_mock()
        post.side_effect = None
        post.return_value.status_code = 429
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_RATE_LIMIT')
        self.assertEqual(post.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    @patch('app.model.time.sleep')
    @patch.dict('os.environ', {'LLM_API_KEY': 'generic-key', 'LLM_BASE_URL': 'https://api.openai.com/v1', 'LLM_MODEL': 'gpt-4o-mini'}, clear=True)
    @patch('app.model.requests.post')
    def test_neutral_llm_env_and_transient_retry(self, post, sleep):
        transient = unittest.mock.Mock(status_code=503)
        good = unittest.mock.Mock(status_code=200)
        good.json.return_value = {'choices': [{'message': {'content': '{"ok": true}'}}]}
        post.side_effect = [transient, good]
        self.assertEqual(NvidiaModel().generate('test', {}, {}), {'ok': True})
        self.assertEqual(post.call_count, 2)
        self.assertEqual(post.call_args.args[0], 'https://api.openai.com/v1/chat/completions')
        self.assertNotIn('chat_template_kwargs', post.call_args.kwargs['json'])

    @patch.dict('os.environ', {'NVIDIA_API_KEY': 'test-key'})
    @patch('app.model.requests.post')
    def test_provider_auth_failure_and_invalid_json_retry(self, post):
        post.return_value.status_code = 401
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_AUTH_FAILED')
        self.assertEqual(post.call_count, 1)

        post.reset_mock()
        post.return_value.status_code = 200
        post.return_value.json.return_value = {'choices': [{'message': {'content': 'not json'}}]}
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_INVALID_OUTPUT')
        self.assertEqual(post.call_count, 3)
        self.assertEqual(post.call_args.kwargs['json']['response_format'], {'type': 'json_object'})

        post.reset_mock()
        bad = unittest.mock.Mock(status_code=200)
        bad.json.return_value = {'choices': [{'message': {'content': 'not json'}}]}
        good = unittest.mock.Mock(status_code=200)
        good.json.return_value = {'choices': [{'message': {'content': '{"ok": true}'}}]}
        post.side_effect = [bad, good]
        self.assertEqual(NvidiaModel().generate('test', {}, {}), {'ok': True})
        self.assertEqual(post.call_count, 2)

class EvidenceAnalysisTest(unittest.TestCase):
    @patch('app.main.model.generate')
    def test_analysis_returns_structured_findings_and_bounded_samples(self, generate):
        generate.return_value = {
            'summary': 'Độ trễ tăng.', 'bottlenecks': [], 'recommendations': [],
            'findings': [{'kind': 'hypothesis', 'title': 'Độ trễ có thể tăng', 'evidence': 'p95 tăng từ 20 ms lên 100 ms',
                          'meaning': 'Một số yêu cầu chậm hơn.', 'nextStep': 'Kiểm tra lại dưới cùng mức tải.'}],
            'limitations': ['Chưa có mục tiêu hiệu năng.'],
        }
        sample = {'elapsedSeconds': 1, 'totalRequests': 3, 'failedRequests': 0, 'throughput': 3,
                  'avgLatencyMs': 15, 'p50LatencyMs': 10, 'p95LatencyMs': 20, 'errorRate': 0, 'activeUsers': 1}
        request = AnalyzeLogRequest(totalRequests=12, failedRequests=0, throughput=3, avgLatencyMs=15,
            p50LatencyMs=10, p95LatencyMs=20, errorRate=0, status='COMPLETED',
            loadConfig={'virtualUsers': 1, 'durationSeconds': 3, 'requestsPerSecond': 1, 'timeoutMs': 5000}, samples=SAMPLES)
        result = main.analyze_log(request)
        self.assertEqual(result.findings[0].kind, 'observation')
        self.assertIn('20', result.findings[0].evidence)
        self.assertTrue(any(f.kind == 'hypothesis' for f in result.findings))
        self.assertEqual(generate.call_args.args[1]['samples'], SAMPLES)
        self.assertIn('tiếng Việt', generate.call_args.args[0])
        self.assertIn('giả thuyết', generate.call_args.args[0])

    def test_analysis_rejects_sensitive_fields_and_too_many_samples(self):
        from pydantic import ValidationError
        metrics = dict(totalRequests=0, failedRequests=0, throughput=0, avgLatencyMs=0, p50LatencyMs=0, p95LatencyMs=0, errorRate=0)
        sample = dict(elapsedSeconds=0, totalRequests=0, failedRequests=0, throughput=0, avgLatencyMs=0,
                      p50LatencyMs=0, p95LatencyMs=0, errorRate=0, activeUsers=0)
        with self.assertRaises(ValidationError):
            AnalyzeLogRequest(**metrics, samples=[sample] * 61)
        with self.assertRaises(ValidationError):
            AnalyzeLogRequest(**metrics, loadConfig={'virtualUsers': 1, 'durationSeconds': 1,
                'requestsPerSecond': 1, 'timeoutMs': 1000, 'targetUrl': 'https://secret'})

    @patch('app.main.model.generate')
    def test_prompt_instructs_vietnamese_without_translating_technical_values(self, generate):
        generate.return_value = {'config': {'name': 'Bài thử', 'targetUrl': 'https://example.com/test', 'httpMethod': 'GET',
            'headers': {}, 'body': None, 'virtualUsers': 1, 'durationSeconds': 3, 'requestsPerSecond': 1, 'timeoutMs': 5000},
            'warnings': ['Cần xác nhận quyền.'], 'requiresApproval': True}
        result = main.parse_prompt(ParsePromptRequest(prompt='Thử API'))
        self.assertEqual(result.config.targetUrl, 'https://example.com/test')
        self.assertEqual(result.warnings, ['Cần xác nhận quyền.'])
        instruction = generate.call_args.args[0]
        self.assertIn('tiếng Việt', instruction)
        self.assertIn('URL', instruction)
        self.assertIn('JSON', instruction)

    @patch('app.main.model.generate')
    def test_no_requests_and_stopped_are_explicit_limitations(self, generate):
        generate.return_value = {'summary': 'Chưa đủ dữ liệu.', 'bottlenecks': [], 'recommendations': [],
                                 'findings': [], 'limitations': []}
        request = AnalyzeLogRequest(totalRequests=0, failedRequests=0, throughput=0, avgLatencyMs=0,
            p50LatencyMs=0, p95LatencyMs=0, errorRate=0, status='STOPPED')
        result = main.analyze_log(request)
        self.assertTrue(any('không có request' in item.lower() for item in result.limitations))
        self.assertTrue(any('đã dừng' in item.lower() for item in result.limitations))

    @patch('app.main.model.generate')
    def test_finding_requires_evidence_and_explicit_kind(self, generate):
        generate.return_value = {'summary': 'Độ trễ tăng', 'bottlenecks': [], 'recommendations': [],
                                 'findings': [{'kind': 'guess', 'title': 'Chậm', 'evidence': '',
                                    'meaning': 'Chậm', 'nextStep': 'Kiểm tra'}], 'limitations': []}
        request = AnalyzeLogRequest(totalRequests=12, failedRequests=0, throughput=1, avgLatencyMs=1,
            p50LatencyMs=1, p95LatencyMs=1, errorRate=0, samples=SAMPLES)
        with self.assertRaises(HTTPException) as error:
            main.analyze_log(request)
        self.assertEqual(error.exception.detail['code'], 'AI_INVALID_OUTPUT')

    @patch('app.main.model.generate')
    def test_analysis_prompt_distinguishes_cumulative_and_interval_metrics(self, generate):
        generate.return_value = {'summary': 'Kết quả.', 'bottlenecks': [], 'recommendations': [], 'findings': [], 'limitations': []}
        request = AnalyzeLogRequest(totalRequests=12, failedRequests=0, throughput=1, avgLatencyMs=1,
            p50LatencyMs=1, p95LatencyMs=1, errorRate=0, samples=SAMPLES)
        main.analyze_log(request)
        instruction = generate.call_args.args[0]
        self.assertIn('cộng dồn', instruction)
        self.assertIn('khoảng gốc', instruction)
        self.assertIn('không đủ bằng chứng', instruction)
        self.assertIn('chỉ tạo giả thuyết', instruction)
        self.assertIn('không ghi chữ số', instruction)

    @patch('app.main.model.generate')
    def test_sparse_samples_return_only_server_facts_without_using_model(self, generate):
        sample = {'elapsedSeconds': 1, 'totalRequests': 3, 'failedRequests': 1, 'throughput': 3,
                  'avgLatencyMs': 15, 'p50LatencyMs': 10, 'p95LatencyMs': 20, 'errorRate': 33.3, 'activeUsers': 1}
        request = AnalyzeLogRequest(totalRequests=3, failedRequests=1, throughput=3, avgLatencyMs=15,
            p50LatencyMs=10, p95LatencyMs=20, errorRate=33.3, status='COMPLETED', samples=[sample, sample])
        result = main.analyze_log(request)
        self.assertEqual(result.analysisMode, 'insufficient_data')
        self.assertIn('3', result.summary)
        self.assertIn('1', result.summary)
        self.assertEqual(result.findings, [])
        self.assertTrue(any('không đủ mẫu' in x.lower() for x in result.limitations))
        generate.assert_not_called()

    @patch('app.main.model.generate')
    def test_model_cannot_supply_observations_or_invented_numeric_claims(self, generate):
        generate.return_value = {'summary': 'p95 bằng 9999 ms', 'bottlenecks': ['9999 lỗi'], 'recommendations': ['Máy chủ hỏng'],
            'findings': [
                {'kind': 'observation', 'title': 'p95 là 9999 ms', 'evidence': '9999 ms', 'meaning': 'Rất chậm', 'nextStep': 'Nâng cấp'},
                {'kind': 'hypothesis', 'title': 'Có 9999 lỗi', 'evidence': '9999 lỗi', 'meaning': 'Máy chủ hỏng', 'nextStep': 'Nâng cấp'},
                {'kind': 'hypothesis', 'title': 'Có thể p95 tăng do quá tải', 'evidence': '9999 lỗi', 'meaning': 'Cần kiểm tra tài nguyên.', 'nextStep': 'Kiểm tra CPU.'}],
            'limitations': ['9999 sample']}
        samples = [dict(SAMPLE, elapsedSeconds=i, totalRequests=i * 5) for i in (1, 2, 3)]
        request = AnalyzeLogRequest(totalRequests=15, failedRequests=1, throughput=3, avgLatencyMs=15,
            p50LatencyMs=10, p95LatencyMs=20, errorRate=6.7, status='COMPLETED', samples=samples)
        result = main.analyze_log(request)
        text = result.model_dump_json()
        self.assertNotIn('9999', text)
        self.assertIn('20', result.summary)
        self.assertTrue(any(f.kind == 'observation' and '20' in f.evidence for f in result.findings))
        self.assertTrue(any(f.kind == 'hypothesis' and 'p95' in f.title and 'chưa được kiểm chứng' in f.evidence for f in result.findings))
        self.assertEqual(result.analysisMode, 'model')

    @patch('app.main.model.generate')
    def test_few_requests_or_duplicate_samples_do_not_support_model_analysis(self, generate):
        samples = [dict(SAMPLE, elapsedSeconds=i, totalRequests=i) for i in (1, 2, 3)]
        args = dict(totalRequests=3, failedRequests=0, throughput=1, avgLatencyMs=1, p50LatencyMs=1,
                    p95LatencyMs=1, errorRate=0, status='COMPLETED', samples=samples)
        self.assertEqual(main.analyze_log(AnalyzeLogRequest(**args)).analysisMode, 'insufficient_data')
        args.update(totalRequests=15, samples=[samples[0]] * 3)
        self.assertEqual(main.analyze_log(AnalyzeLogRequest(**args)).analysisMode, 'insufficient_data')
        generate.assert_not_called()

    @patch('app.main.model.generate')
    def test_observations_compare_first_and_last_sample_as_recorded_facts(self, generate):
        generate.return_value = {'summary': 'x', 'bottlenecks': [], 'recommendations': [], 'findings': [], 'limitations': []}
        samples = [dict(SAMPLE, elapsedSeconds=1, totalRequests=4, failedRequests=0, p95LatencyMs=25, throughput=2),
                   dict(SAMPLE, elapsedSeconds=5, totalRequests=8, failedRequests=1, p95LatencyMs=60, throughput=3),
                   dict(SAMPLE, elapsedSeconds=10, totalRequests=12, failedRequests=2, p95LatencyMs=120, throughput=1)]
        request = AnalyzeLogRequest(totalRequests=12, failedRequests=2, throughput=1, avgLatencyMs=50,
            p50LatencyMs=20, p95LatencyMs=120, errorRate=16.7, samples=samples)
        result = main.analyze_log(request)
        facts = ' '.join(f.evidence for f in result.findings if f.kind == 'observation')
        self.assertIn('25 ms', facts)
        self.assertIn('120 ms', facts)
        self.assertIn('0 lỗi', facts)
        self.assertIn('2 lỗi', facts)
        self.assertIn('2 request/giây', facts)
        self.assertIn('1 request/giây', facts)
        self.assertIn('1 giây', facts)
        self.assertIn('10 giây', facts)

    @patch('app.main.model.generate')
    def test_observations_include_error_breakdown_and_anomaly_warning(self, generate):
        generate.return_value = {'summary': 'x', 'bottlenecks': [], 'recommendations': [], 'findings': [], 'limitations': []}
        request = AnalyzeLogRequest(
            totalRequests=20, failedRequests=5, throughput=4, avgLatencyMs=50,
            p50LatencyMs=30, p95LatencyMs=120, errorRate=25, samples=SAMPLES,
            errorBreakdown={'http4xx': 0, 'http429': 2, 'http5xx': 1, 'timeout': 2, 'network': 0},
            anomalyWarning='Tỷ lệ lỗi vượt ngưỡng 5% tại giây thứ 2.',
        )
        result = main.analyze_log(request)
        observations = [f for f in result.findings if f.kind == 'observation']
        breakdown_obs = [f for f in observations if 'Phân loại lỗi' in f.title]
        anomaly_obs = [f for f in observations if 'bất thường' in f.title.lower()]
        self.assertEqual(len(breakdown_obs), 1)
        self.assertIn('HTTP 429: 2', breakdown_obs[0].evidence)
        self.assertIn('HTTP 5xx: 1', breakdown_obs[0].evidence)
        self.assertIn('Timeout: 2', breakdown_obs[0].evidence)
        self.assertNotIn('HTTP 4xx', breakdown_obs[0].evidence)
        self.assertNotIn('Network', breakdown_obs[0].evidence)
        self.assertEqual(len(anomaly_obs), 1)
        self.assertIn('Tỷ lệ lỗi vượt ngưỡng 5%', anomaly_obs[0].evidence)

    def test_make_validator_uses_draft202012_and_checks_schema_draft(self):
        from jsonschema import validators
        from app.schemas import ValidateMockDataRequest
        for draft in (
            'http://json-schema.org/draft-04/schema#',
            'http://json-schema.org/draft-06/schema#',
            'http://json-schema.org/draft-07/schema#',
            'https://json-schema.org/draft/2019-09/schema',
            'https://json-schema.org/draft/2020-12/schema',
        ):
            validator = main.make_validator({'$schema': draft, 'type': 'object'})
            self.assertIsInstance(validator, validators.Draft202012Validator)
        with self.assertRaises(HTTPException) as error:
            main.validate_mockdata(ValidateMockDataRequest(
                schemaJson={'$schema': 'http://json-schema.org/draft-03/schema#', 'type': 'object'},
                payloads=[{}],
            ))
        self.assertEqual(error.exception.status_code, 400)
        self.assertEqual(error.exception.detail['code'], 'INVALID_SCHEMA')

    @patch('app.main.model.generate')
    def test_bottleneck_detector_produces_grounded_bottlenecks_and_recommendations(self, generate):
        generate.return_value = {
            'summary': '',
            'bottlenecks': ['9999 fake'],
            'recommendations': ['Rà soát truy vấn cơ sở dữ liệu khi tải đồng thời tăng.', 'Fake 9999 rec'],
            'findings': [],
            'limitations': [],
        }
        samples = [
            dict(SAMPLE, elapsedSeconds=1, totalRequests=10, p95LatencyMs=40, intervalP95Ms=45, throughput=10),
            dict(SAMPLE, elapsedSeconds=2, totalRequests=20, p95LatencyMs=120, intervalP95Ms=950, throughput=10),
            dict(SAMPLE, elapsedSeconds=3, totalRequests=30, p95LatencyMs=220, intervalP95Ms=300, throughput=10),
        ]
        request = AnalyzeLogRequest(
            totalRequests=30, failedRequests=6, throughput=10, avgLatencyMs=120,
            p50LatencyMs=40, p95LatencyMs=220, p99LatencyMs=450, maxLatencyMs=950,
            errorRate=20, status='STOPPED', stopReason='ANOMALY',
            loadConfig={'virtualUsers': 10, 'durationSeconds': 3, 'requestsPerSecond': 50, 'timeoutMs': 1000, 'loadProfile': 'CONSTANT', 'rampUpSeconds': 0},
            samples=samples,
            errorBreakdown={'http429': 4, 'timeout': 2},
            anomalyWarning='Early anomaly detected',
        )
        result = main.analyze_log(request)
        text = result.model_dump_json()
        self.assertNotIn('9999', text)
        self.assertTrue(any('HTTP 429: 4' in b for b in result.bottlenecks))
        self.assertTrue(any('Timeout: 2' in b for b in result.bottlenecks))
        self.assertTrue(any('Interval p95' in b for b in result.bottlenecks))
        self.assertTrue(any('p99 = 450' in b for b in result.bottlenecks))
        self.assertTrue(any('Cài đặt' in r or 'rate limit' in r.lower() for r in result.recommendations))
        self.assertIn('Rà soát truy vấn cơ sở dữ liệu khi tải đồng thời tăng.', result.recommendations)
        self.assertLess(result.healthScore, 50)
        self.assertIn(result.healthGrade, ('D', 'F'))
        self.assertTrue(result.capacitySummary)

    @patch('app.main.model.generate')
    def test_health_score_and_capacity_summary_for_healthy_and_breaking_runs(self, generate):
        generate.return_value = {'summary': '', 'bottlenecks': [], 'recommendations': [], 'findings': [], 'limitations': []}
        healthy_samples = [
            dict(SAMPLE, elapsedSeconds=1, totalRequests=20, throughput=20, p95LatencyMs=18, intervalP95Ms=20, intervalErrorRate=0, activeUsers=10),
            dict(SAMPLE, elapsedSeconds=2, totalRequests=40, throughput=20, p95LatencyMs=22, intervalP95Ms=24, intervalErrorRate=0, activeUsers=15),
            dict(SAMPLE, elapsedSeconds=3, totalRequests=60, throughput=20, p95LatencyMs=25, intervalP95Ms=25, intervalErrorRate=0, activeUsers=20),
        ]
        healthy = main.analyze_log(AnalyzeLogRequest(
            totalRequests=60, failedRequests=0, throughput=20, avgLatencyMs=15,
            p50LatencyMs=12, p95LatencyMs=25, p99LatencyMs=30, maxLatencyMs=40,
            errorRate=0, status='COMPLETED', samples=healthy_samples,
        ))
        self.assertGreaterEqual(healthy.healthScore, 95)
        self.assertEqual(healthy.healthGrade, 'A')
        self.assertIn('20 req/s', healthy.capacitySummary)

        degraded_samples = [
            dict(SAMPLE, elapsedSeconds=1, totalRequests=15, throughput=15, p95LatencyMs=30, intervalP95Ms=35, intervalErrorRate=0, activeUsers=5),
            dict(SAMPLE, elapsedSeconds=2, totalRequests=45, throughput=30, p95LatencyMs=60, intervalP95Ms=80, intervalErrorRate=0, activeUsers=10),
            dict(SAMPLE, elapsedSeconds=3, totalRequests=75, throughput=30, p95LatencyMs=900, intervalP95Ms=1400, intervalErrorRate=12, activeUsers=20),
        ]
        degraded = main.analyze_log(AnalyzeLogRequest(
            totalRequests=75, failedRequests=5, throughput=25, avgLatencyMs=250,
            p50LatencyMs=50, p95LatencyMs=900, p99LatencyMs=1500, maxLatencyMs=1600,
            errorRate=6.7, status='COMPLETED', samples=degraded_samples,
            errorBreakdown={'http5xx': 5},
        ))
        self.assertIn('30 req/s', degraded.capacitySummary)
        self.assertIn('giây 3', degraded.capacitySummary)
        self.assertIn(degraded.healthGrade, ('C', 'D', 'F'))
