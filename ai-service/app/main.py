import os
import secrets
import re
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from jsonschema import validators, exceptions as schema_errors
from pydantic import ValidationError
from .model import NvidiaModel
from .schemas import (ParsePromptRequest, ParsePromptResponse, GenerateMockDataRequest,
                      GenerateMockDataResponse, AnalyzeLogRequest, AnalyzeLogResponse,
                      ValidateMockDataRequest, ExplainSuiteCaseRequest, ExplainSuiteCaseResponse)

app = FastAPI(title='LoadSense AI Service', version='0.3.0')
model = NvidiaModel()


@app.middleware('http')
async def authenticate(request: Request, call_next):
    if request.url.path != '/health':
        token = os.getenv('AI_SERVICE_TOKEN', '')
        if not token or not secrets.compare_digest(request.headers.get('X-AI-Service-Token', ''), token):
            return JSONResponse(status_code=401, content={'detail': {'code': 'AI_UNAUTHORIZED', 'message': 'AI Service không được phép truy cập'}})
    return await call_next(request)


def output(response_type, instruction, request):
    try:
        result = model.generate(instruction, request.model_dump(), response_type.model_json_schema())
        return response_type.model_validate(result)
    except ValidationError:
        raise HTTPException(502, detail={'code': 'AI_INVALID_OUTPUT', 'message': 'Kết quả model không khớp định dạng yêu cầu'}) from None


@app.get('/health')
def health():
    enabled = bool((os.getenv('LLM_API_KEY') or os.getenv('NVIDIA_API_KEY') or '').strip())
    return {'status': 'UP', 'aiEnabled': enabled, 'modelStatus': 'CONFIGURED' if enabled else 'NOT_CONFIGURED'}


@app.post('/parse-prompt', response_model=ParsePromptResponse)
def parse_prompt(request: ParsePromptRequest):
    result = output(
        ParsePromptResponse,
        'Tạo cấu hình kiểm thử từ yêu cầu của người dùng. Viết tên cấu hình và cảnh báo bằng tiếng Việt, kể cả khi yêu cầu bằng tiếng Anh. Hỗ trợ loadProfile (CONSTANT, RAMP_UP, STEP_STRESS, SPIKE) và rampUpSeconds nếu được nhắc. Giữ nguyên URL, HTTP method, tên header, JSON và các giá trị kỹ thuật; không dịch chúng. Nêu các giả định còn thiếu, không thêm thông tin đăng nhập. Người dùng phải phê duyệt.',
        request,
    )
    result.requiresApproval = True
    return result


@app.post('/generate-mockdata', response_model=GenerateMockDataResponse)
def generate_mockdata(request: GenerateMockDataRequest):
    validator = make_validator(request.schemaJson)
    result = output(GenerateMockDataResponse, 'Generate the exact requested number of synthetic JSON objects matching schemaJson.', request)
    try:
        if len(result.payloads) != request.count or any(not validator.is_valid(p) for p in result.payloads):
            raise HTTPException(502, detail={'code': 'AI_INVALID_OUTPUT', 'message': 'Payload không khớp số lượng hoặc JSON Schema'})
    except schema_errors._RefResolutionError:
        raise HTTPException(400, detail={'code': 'INVALID_SCHEMA', 'message': 'Tham chiếu trong JSON Schema không hợp lệ'}) from None
    return result


ALLOWED_SCHEMAS = frozenset({
    'http://json-schema.org/draft-04/schema#',
    'http://json-schema.org/draft-06/schema#',
    'http://json-schema.org/draft-07/schema#',
    'https://json-schema.org/draft/2019-09/schema',
    'https://json-schema.org/draft/2020-12/schema',
})


def make_validator(schema):
    try:
        def check_node(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key in ('$ref', '$dynamicRef', '$recursiveRef') and isinstance(item, str) and not item.startswith('#'):
                        raise ValueError('remote reference')
                    if key == '$schema' and (not isinstance(item, str) or item not in ALLOWED_SCHEMAS):
                        raise ValueError('unsupported schema draft')
                    check_node(item)
            elif isinstance(value, list):
                for item in value:
                    check_node(item)
        check_node(schema)
        validators.Draft202012Validator.check_schema(schema)
        return validators.Draft202012Validator(schema)
    except (schema_errors.SchemaError, ValueError):
        raise HTTPException(400, detail={'code': 'INVALID_SCHEMA', 'message': 'JSON Schema không hợp lệ'}) from None


@app.post('/validate-mockdata')
def validate_mockdata(request: ValidateMockDataRequest):
    validator = make_validator(request.schemaJson)
    try:
        if any(not validator.is_valid(p) for p in request.payloads):
            raise HTTPException(400, detail={'code': 'INVALID_PAYLOAD', 'message': 'Payload không khớp JSON Schema'})
    except schema_errors._RefResolutionError:
        raise HTTPException(400, detail={'code': 'INVALID_SCHEMA', 'message': 'Tham chiếu trong JSON Schema không hợp lệ'}) from None
    return {'valid': True}


def detect_bottlenecks_and_recommendations(request: AnalyzeLogRequest) -> tuple[list[str], list[str]]:
    """Deterministic detector backed strictly by observed metrics and error counters."""
    bottlenecks: list[str] = []
    recommendations: list[str] = []
    breakdown = request.errorBreakdown or {}

    if breakdown.get('http429', 0) > 0:
        count = breakdown['http429']
        bottlenecks.append(f'API đích giới hạn tần suất (HTTP 429: {count} request).')
        recommendations.append('Giảm RPS mục tiêu hoặc nới ngưỡng rate limit / throttling trên API đích.')

    if breakdown.get('http5xx', 0) > 0:
        count = breakdown['http5xx']
        bottlenecks.append(f'Máy chủ đích phát sinh lỗi nội bộ (HTTP 5xx: {count} request).')
        recommendations.append('Kiểm tra log ứng dụng đích, kích thước connection pool cơ sở dữ liệu và tài nguyên CPU/RAM khi tải tăng.')

    if breakdown.get('timeout', 0) > 0:
        count = breakdown['timeout']
        timeout_hint = f' (timeout cấu hình {request.loadConfig.timeoutMs} ms)' if request.loadConfig else ''
        bottlenecks.append(f'Xảy ra hết thời gian chờ phản hồi (Timeout: {count} request){timeout_hint}.')
        recommendations.append('Tối ưu truy vấn chậm hoặc xử lý bất đồng bộ ở API đích; cân nhắc tăng timeoutMs nếu tác vụ vốn dài.')

    if breakdown.get('network', 0) > 0:
        count = breakdown['network']
        bottlenecks.append(f'Lỗi kết nối mạng ở tầng vận chuyển (Network: {count} request).')
        recommendations.append('Kiểm tra giới hạn socket/file descriptor, keep-alive hoặc kết nối mạng giữa LoadSense và API đích.')

    if breakdown.get('http4xx', 0) > 0:
        count = breakdown['http4xx']
        bottlenecks.append(f'API đích từ chối yêu cầu (HTTP 4xx: {count} request).')
        recommendations.append('Kiểm tra lại dữ liệu mock, header xác thực (Authorization) hoặc tham số URL của bài kiểm thử.')

    if len(request.samples) >= 3:
        first = min(request.samples, key=lambda s: s.elapsedSeconds)
        last = max(request.samples, key=lambda s: s.elapsedSeconds)
        if first.p95LatencyMs > 0 and last.p95LatencyMs >= first.p95LatencyMs * 2 and last.p95LatencyMs >= 100:
            bottlenecks.append(
                f'Độ trễ p95 tích lũy tăng mạnh từ {first.p95LatencyMs:g} ms lên {last.p95LatencyMs:g} ms theo thời gian.'
            )
            recommendations.append('Chạy chế độ RAMP_UP hoặc STEP_STRESS để xác định chính xác ngưỡng tải bắt đầu gây nghẽn hàng đợi.')
        max_interval_p95 = max((s.intervalP95Ms for s in request.samples), default=0)
        if max_interval_p95 >= max(500.0, request.p95LatencyMs * 1.8):
            bottlenecks.append(
                f'Xuất hiện xung tăng độ trễ theo nhịp (Interval p95 đạt đỉnh {max_interval_p95:g} ms so với p95 tổng hợp {request.p95LatencyMs:g} ms).'
            )
            recommendations.append('Đối chiếu mốc thời gian đạt đỉnh với hoạt động thu gom rác (GC), khóa DB hoặc tác vụ nền trên hệ thống đích.')

    if request.p99LatencyMs > 0 and request.p50LatencyMs > 0 and request.p99LatencyMs >= max(150.0, request.p50LatencyMs * 3):
        ratio = request.p99LatencyMs / request.p50LatencyMs
        bottlenecks.append(
            f'Độ trễ đuôi dài (p99 = {request.p99LatencyMs:g} ms, gấp {ratio:.1f}x so với trung vị p50 = {request.p50LatencyMs:g} ms).'
        )
        recommendations.append('Kiểm tra nguyên nhân gây chậm cục bộ cho nhóm 1% request chậm nhất (cache miss, cold start hoặc tranh chấp lock).')

    if request.loadConfig and request.loadConfig.requestsPerSecond >= 10 and request.totalRequests >= 10:
        is_constant = request.loadConfig.loadProfile == 'CONSTANT'
        if is_constant and request.throughput < request.loadConfig.requestsPerSecond * 0.7 and request.avgLatencyMs > 0:
            bottlenecks.append(
                f'Thông lượng thực tế ({request.throughput:g} req/s) thấp hơn mục tiêu ({request.loadConfig.requestsPerSecond} req/s) do độ trễ trung bình {request.avgLatencyMs:g} ms.'
            )
            recommendations.append('Theo định luật Little, cần tối ưu thời gian xử lý mỗi request hoặc tăng số lượng virtual users nếu API đích chưa bão hòa.')

    if request.stopReason == 'ANOMALY' or (request.anomalyWarning and request.anomalyWarning.strip()):
        reason_detail = f' ({request.anomalyWarning.strip()})' if request.anomalyWarning else ''
        bottlenecks.append(f'Hệ thống ghi nhận cảnh báo bất thường sớm{reason_detail}.')
        if request.stopReason == 'ANOMALY':
            recommendations.append('Kiểm tra nguyên nhân vượt ngưỡng lỗi/p95 hoặc điều chỉnh ngưỡng bảo vệ trong mục Cài đặt trước khi thử lại.')

    if not bottlenecks and request.totalRequests >= 10:
        recommendations.append(
            f'Hệ thống đích vận hành ổn định trong phạm vi đo (p95 = {request.p95LatencyMs:g} ms, tỷ lệ lỗi = {request.errorRate:g}%). Có thể dùng profile STEP_STRESS để dò ngưỡng chịu tải tối đa.'
        )

    return bottlenecks, list(dict.fromkeys(recommendations))


def compute_health_and_capacity(request: AnalyzeLogRequest) -> tuple[int, str, str]:
    """Compute a metric-grounded health score (0-100), grade (A-F) and safe capacity summary."""
    score = 100.0
    score -= min(50.0, request.errorRate * 2.5)
    breakdown = request.errorBreakdown or {}
    if breakdown.get('http5xx', 0) > 0:
        score -= 10.0
    if breakdown.get('timeout', 0) > 0 or breakdown.get('network', 0) > 0:
        score -= 8.0
    if breakdown.get('http429', 0) > 0:
        score -= 6.0
    if request.p95LatencyMs > 200:
        score -= min(25.0, (request.p95LatencyMs - 200.0) / 80.0)
    if request.p99LatencyMs > 500:
        score -= min(15.0, (request.p99LatencyMs - 500.0) / 150.0)
    if request.stopReason == 'ANOMALY':
        score -= 12.0

    health_score = max(0, min(100, int(round(score))))
    if health_score >= 90:
        grade = 'A'
    elif health_score >= 75:
        grade = 'B'
    elif health_score >= 60:
        grade = 'C'
    elif health_score >= 40:
        grade = 'D'
    else:
        grade = 'F'

    ordered = sorted(request.samples, key=lambda s: s.elapsedSeconds)
    safe_samples = [
        s for s in ordered
        if s.totalRequests > 0 and s.intervalErrorRate <= 0.5 and (s.intervalP95Ms or s.p95LatencyMs) < 1000.0
    ]
    bad_sample = next(
        (s for s in ordered if s.intervalErrorRate > 1.0 or s.intervalP95Ms >= 1000.0),
        None,
    )

    if bad_sample and safe_samples:
        peak_safe = max(safe_samples, key=lambda s: s.throughput)
        capacity_summary = (
            f'Ngưỡng an toàn quan sát: tối đa ~{peak_safe.throughput:g} req/s ({peak_safe.activeUsers} VUs); '
            f'bắt đầu suy giảm từ giây {bad_sample.elapsedSeconds:g} ({bad_sample.throughput:g} req/s, '
            f'lỗi nhịp {bad_sample.intervalErrorRate:g}%, interval p95 {bad_sample.intervalP95Ms:g} ms).'
        )
    elif safe_samples:
        peak_safe = max(safe_samples, key=lambda s: s.throughput)
        capacity_summary = (
            f'Hệ thống chịu tải ổn định tới mức đo tối đa ~{peak_safe.throughput:g} req/s '
            f'({peak_safe.activeUsers} VUs, p95 {request.p95LatencyMs:g} ms, chưa chạm điểm gãy).'
        )
    elif request.totalRequests > 0:
        capacity_summary = (
            f'Chưa ghi nhận ngưỡng an toàn hoàn toàn sạch lỗi (tổng tải {request.throughput:g} req/s, '
            f'lỗi {request.errorRate:g}%, p95 {request.p95LatencyMs:g} ms).'
        )
    else:
        capacity_summary = 'Chưa có đủ dữ liệu mẫu để ước lượng ngưỡng chịu tải.'

    return health_score, grade, capacity_summary


def _contains_digits(text: str) -> bool:
    sanitized = re.sub(r'\bp(?:50|95|99)\b', '', text, flags=re.IGNORECASE)
    return any(ch.isdigit() for ch in sanitized)


@app.post('/analyze-log', response_model=AnalyzeLogResponse)
def analyze_log(request: AnalyzeLogRequest):
    enough_samples = (len({sample.elapsedSeconds for sample in request.samples}) >= 3
                      and len({sample.totalRequests for sample in request.samples}) >= 3)
    p99_part = f' p99 là {request.p99LatencyMs:g} ms.' if request.p99LatencyMs > 0 else ''
    summary = (f'Tổng cộng {request.totalRequests} request, {request.failedRequests} request lỗi '
               f'({request.errorRate:g}%). p95 là {request.p95LatencyMs:g} ms.{p99_part}')
    det_bottlenecks, det_recommendations = detect_bottlenecks_and_recommendations(request)
    health_score, health_grade, capacity_summary = compute_health_and_capacity(request)

    if not enough_samples or request.totalRequests < 10:
        limitations = []
        if not enough_samples:
            limitations.append('Không đủ mẫu theo thời gian để đánh giá xu hướng.')
        if 0 < request.totalRequests < 10:
            limitations.append('Không đủ request để phân tích đáng tin cậy.')
        if request.status == 'STOPPED':
            limitations.append('Bài kiểm thử đã dừng trước khi hoàn thành.')
        elif request.status == 'FAILED':
            limitations.append('Bài kiểm thử gặp lỗi trước khi hoàn thành.')
        if request.totalRequests == 0:
            limitations.append('Không có request nào được ghi nhận.')
        return AnalyzeLogResponse(
            summary=summary,
            bottlenecks=det_bottlenecks,
            recommendations=det_recommendations,
            findings=[],
            limitations=limitations,
            analysisMode='insufficient_data',
            healthScore=health_score,
            healthGrade=health_grade,
            capacitySummary=capacity_summary,
        )

    result = output(
        AnalyzeLogResponse,
        '''Trả lời bằng tiếng Việt và chỉ tạo giả thuyết (kind="hypothesis") cùng tối đa hai gợi ý khắc phục (recommendations) ở mức khái niệm dựa trên dữ liệu đầu vào. Phân biệt rõ p50/p95/p99 cộng dồn từ đầu bài test với intervalP95Ms/intervalErrorRate/throughput của từng khoảng gốc. Tuyệt đối không ghi chữ số, giá trị đo, mốc thời gian, tỷ lệ hay HTTP status bằng chữ hoặc chữ số trong hypothesis hoặc recommendations. Nếu dữ liệu không đủ bằng chứng thì giữ ngắn gọn là không đủ bằng chứng. evidence ghi ngắn gọn là giả thuyết cần kiểm chứng. Không khuyên tăng tải khi chưa xác nhận quyền. Để trống summary, bottlenecks và limitations vì hệ thống tự tính nội dung đó.''',
        request,
    )
    # The model's free-form metrics and observations are not trusted. Only
    # clearly labelled hypotheses and descriptive recommendations without model-authored numeric claims survive.
    hypotheses = []
    for finding in result.findings:
        text = ' '.join((finding.title, finding.meaning, finding.nextStep))
        if finding.kind == 'hypothesis' and not _contains_digits(text):
            finding.evidence = 'Giả thuyết AI chưa được kiểm chứng; hãy đối chiếu với số liệu và kiểm tra thêm.'
            hypotheses.append(finding)

    ai_recommendations = [
        rec.strip()
        for rec in result.recommendations
        if rec and rec.strip() and not _contains_digits(rec)
    ][:2]
    merged_recommendations = list(dict.fromkeys([*det_recommendations, *ai_recommendations]))

    first, last = min(request.samples, key=lambda s: s.elapsedSeconds), max(request.samples, key=lambda s: s.elapsedSeconds)
    start, end = f'{first.elapsedSeconds:g} giây', f'{last.elapsedSeconds:g} giây'
    observations = [
        {'kind': 'observation', 'title': 'Số liệu tổng hợp', 'evidence': summary,
         'meaning': 'Đây là số liệu ghi nhận trong bài kiểm thử, chưa xác định nguyên nhân.',
         'nextStep': 'Đối chiếu với mục tiêu hiệu năng và kiểm tra thêm nếu cần.'},
        {'kind': 'observation', 'title': 'p95 cộng dồn tại hai mốc',
         'evidence': f'{start}: {first.p95LatencyMs:g} ms; {end}: {last.p95LatencyMs:g} ms.',
         'meaning': 'Đây là p95 cộng dồn tính từ đầu bài test tại từng mốc, không phải độ trễ riêng của từng khoảng.',
         'nextStep': 'So sánh với mục tiêu độ trễ của bạn nếu có.'},
        {'kind': 'observation', 'title': 'Số lỗi cộng dồn tại hai mốc',
         'evidence': f'{start}: {first.failedRequests} lỗi; {end}: {last.failedRequests} lỗi.',
         'meaning': 'Số lỗi được cộng dồn từ đầu bài test.',
         'nextStep': 'Kiểm tra chi tiết lỗi nếu bạn có quyền truy cập.'},
        {'kind': 'observation', 'title': 'Throughput tại hai mốc',
         'evidence': f'{start}: {first.throughput:g} request/giây; {end}: {last.throughput:g} request/giây.',
         'meaning': 'Mỗi giá trị đo khoảng lấy mẫu gốc ngay trước mốc; không mô tả toàn bộ thời gian giữa hai mốc.',
         'nextStep': 'Xem các mẫu còn lại để tìm hiểu thêm.'},
    ]
    error_labels = (
        ('http4xx', 'HTTP 4xx'),
        ('http429', 'HTTP 429'),
        ('http5xx', 'HTTP 5xx'),
        ('timeout', 'Timeout'),
        ('network', 'Network'),
    )
    breakdown_parts = [
        f'{label}: {request.errorBreakdown[key]}'
        for key, label in error_labels
        if request.errorBreakdown.get(key, 0) > 0
    ]
    if breakdown_parts:
        observations.append({
            'kind': 'observation',
            'title': 'Phân loại lỗi ghi nhận',
            'evidence': '; '.join(breakdown_parts) + '.',
            'meaning': 'Đây là số lượng lỗi được phân loại theo nhóm trong quá trình chạy bài kiểm thử.',
            'nextStep': 'Kiểm tra log máy chủ hoặc giới hạn tốc độ tương ứng với nhóm lỗi chiếm tỷ lệ cao.',
        })
    if request.anomalyWarning and request.anomalyWarning.strip():
        observations.append({
            'kind': 'observation',
            'title': 'Cảnh báo bất thường sớm',
            'evidence': request.anomalyWarning.strip(),
            'meaning': 'Hệ thống đã phát hiện dấu hiệu bất thường về tỷ lệ lỗi hoặc độ trễ p95 trong khi chạy.',
            'nextStep': 'Đối chiếu mốc thời gian xảy ra cảnh báo với biểu đồ tải và tài nguyên hệ thống đích.',
        })
    final_result = AnalyzeLogResponse(
        summary=summary,
        bottlenecks=det_bottlenecks,
        recommendations=merged_recommendations,
        findings=[*observations, *hypotheses],
        limitations=['Giả thuyết AI cần được kiểm chứng; điểm nghẽn được xác định dựa trên ngưỡng quan sát từ số liệu đo.'],
        analysisMode='model',
        healthScore=health_score,
        healthGrade=health_grade,
        capacitySummary=capacity_summary,
    )
    if request.status == 'STOPPED':
        if request.stopReason == 'ANOMALY':
            final_result.limitations.insert(0, 'Bài kiểm thử tự động dừng bảo vệ do bất thường liên tiếp.')
        else:
            final_result.limitations.insert(0, 'Bài kiểm thử đã dừng trước khi hoàn thành; số liệu có thể chưa đại diện.')
    elif request.status == 'FAILED':
        final_result.limitations.insert(0, 'Bài kiểm thử gặp lỗi; số liệu có thể chưa đại diện.')
    return final_result


@app.post('/explain-suite-case', response_model=ExplainSuiteCaseResponse)
def explain_suite_case(request: ExplainSuiteCaseRequest):
    result = output(
        ExplainSuiteCaseResponse,
        (
            'Bạn là chuyên gia QA & Kiến trúc sư Hệ thống hỗ trợ Tester đọc kết quả kiểm thử tự động. '
            'Dựa trên dữ liệu của Test Case (caseId, name, targetUrl, httpMethod, status, httpStatus, latencyMs, '
            'visitedPages, pageTitle, headings, excerpt, failureSummary), hãy trả lời hoàn toàn bằng tiếng Việt dễ hiểu: '
            '1) previewSummary: Tóm tắt ngắn gọn trang/endpoint này hiển thị nội dung gì, chức năng nghiệp vụ là gì và hành trình đi từ trang chủ qua các bước nào. '
            '2) errorExplanation: Giải thích rõ hiện tượng lỗi (hoặc xác nhận các kiểm tra đã vượt qua tốt nếu PASSED) để Tester không chuyên sâu code cũng hiểu ngay. '
            '3) causeAnalysis: Phân tích nguyên nhân kỹ thuật hoặc dữ liệu khả dĩ nhất gây ra kết quả này. '
            '4) fixSuggestions: Đưa ra các bước kiểm tra và hướng khắc phục cụ thể theo thứ tự ưu tiên. '
            '5) fixPrompt: Soạn một prompt súc tích, đầy đủ thông tin kỹ thuật để Developer có thể copy gửi cho AI Coding Agent sửa lỗi.'
        ),
        request,
    )
    result.analysisMode = 'model'
    return result

