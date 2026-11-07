import unittest
from unittest.mock import patch
from app import main
from app.schemas import ParsePromptRequest, GenerateMockDataRequest, AnalyzeLogRequest
from fastapi import HTTPException
import requests
from app.model import NvidiaModel

SAMPLE = {'elapsedSeconds': 1, 'totalRequests': 1, 'failedRequests': 0, 'throughput': 1, 'avgLatencyMs': 1, 'p50LatencyMs': 1, 'p95LatencyMs': 1, 'errorRate': 0, 'activeUsers': 1}
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

    @patch.dict('os.environ', {'NVIDIA_API_KEY': 'test-key'})
    @patch('app.model.requests.post')
    def test_provider_timeout_and_rate_limit(self, post):
        post.side_effect = requests.Timeout()
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_TIMEOUT')
        post.side_effect = None
        post.return_value.status_code = 429
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_RATE_LIMIT')

    @patch.dict('os.environ', {'NVIDIA_API_KEY': 'test-key'})
    @patch('app.model.requests.post')
    def test_provider_auth_failure_and_invalid_json(self, post):
        post.return_value.status_code = 401
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_AUTH_FAILED')
        post.return_value.status_code = 200
        post.return_value.json.return_value = {'choices': [{'message': {'content': 'not json'}}]}
        with self.assertRaises(HTTPException) as error:
            NvidiaModel().generate('test', {}, {})
        self.assertEqual(error.exception.detail['code'], 'AI_INVALID_OUTPUT')

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
