import os
import secrets
import re
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from jsonschema import validators, exceptions as schema_errors
from pydantic import ValidationError
from .model import NvidiaModel
from .schemas import (ParsePromptRequest, ParsePromptResponse, GenerateMockDataRequest,
                      GenerateMockDataResponse, AnalyzeLogRequest, AnalyzeLogResponse, ValidateMockDataRequest)

app = FastAPI(title='LoadSense AI Service', version='0.2.0')
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
    enabled = bool(os.getenv('NVIDIA_API_KEY', '').strip())
    return {'status': 'UP', 'aiEnabled': enabled, 'modelStatus': 'CONFIGURED' if enabled else 'NOT_CONFIGURED'}


@app.post('/parse-prompt', response_model=ParsePromptResponse)
def parse_prompt(request: ParsePromptRequest):
    result = output(ParsePromptResponse, 'Tạo cấu hình kiểm thử từ yêu cầu của người dùng. Viết tên cấu hình và cảnh báo bằng tiếng Việt, kể cả khi yêu cầu bằng tiếng Anh. Giữ nguyên URL, HTTP method, tên header, JSON và các giá trị kỹ thuật; không dịch chúng. Nêu các giả định còn thiếu, không thêm thông tin đăng nhập. Người dùng phải phê duyệt.', request)
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


def make_validator(schema):
    try:
        def reject_remote(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key in ('$ref', '$dynamicRef', '$recursiveRef') and isinstance(item, str) and not item.startswith('#'):
                        raise ValueError('remote reference')
                    reject_remote(item)
            elif isinstance(value, list):
                for item in value: reject_remote(item)
        reject_remote(schema)
        validator_class = validators.validator_for(schema)
        validator_class.check_schema(schema)
        return validator_class(schema)
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


@app.post('/analyze-log', response_model=AnalyzeLogResponse)
def analyze_log(request: AnalyzeLogRequest):
    enough_samples = (len({sample.elapsedSeconds for sample in request.samples}) >= 3
                      and len({sample.totalRequests for sample in request.samples}) >= 3)
    summary = (f'Tổng cộng {request.totalRequests} request, {request.failedRequests} request lỗi '
               f'({request.errorRate:g}%). p95 là {request.p95LatencyMs:g} ms.')
    if not enough_samples or request.totalRequests < 10:
        limitations = []
        if not enough_samples:
            limitations.append('Không đủ mẫu theo thời gian để đánh giá xu hướng.')
        if 0 < request.totalRequests < 10:
            limitations.append('Không đủ request để phân tích đáng tin cậy.')
        if request.status == 'STOPPED':
            limitations.append('Bài kiểm thử đã dừng trước khi hoàn thành; số liệu có thể chưa đại diện.')
        elif request.status == 'FAILED':
            limitations.append('Bài kiểm thử gặp lỗi; số liệu có thể chưa đại diện.')
        if request.totalRequests == 0:
            summary = 'Không có request để phân tích hiệu năng.'
            limitations.append('Không có request, nên không thể đánh giá hiệu năng hay nguyên nhân.')
        return AnalyzeLogResponse(summary=summary, bottlenecks=[], findings=[],
            recommendations=['Kiểm tra cấu hình và chạy lại nếu bạn có quyền kiểm thử.'],
            limitations=limitations, analysisMode='insufficient_data')
    result = output(AnalyzeLogResponse, '''Phân tích dữ liệu kiểm thử cho người mới hoàn toàn bằng tiếng Việt.
Trong samples, totalRequests, failedRequests, avgLatencyMs, p50LatencyMs, p95LatencyMs và errorRate là số liệu cộng dồn từ đầu bài kiểm thử, không phải số liệu của riêng khoảng giữa hai mẫu. throughput là request/giây của khoảng gốc ngay trước mẫu đó. Các mẫu có thể được chọn thưa, vì vậy throughput không đại diện toàn bộ khoảng giữa hai mẫu được gửi. Không suy diễn thời điểm thay đổi chính xác từ các mẫu thưa hoặc mô tả các chỉ số cộng dồn như chỉ số của từng khoảng.
Nếu hai mẫu thưa có cùng throughput, không đủ bằng chứng để nói throughput ổn định giữa chúng; chỉ mô tả các giá trị đo tại từng mẫu. p95 cộng dồn tăng giữa hai mẫu không chứng minh các request cuối cùng chậm hơn hoặc độ trễ tăng đều. Nếu số lỗi cộng dồn tăng, chỉ khẳng định lỗi xuất hiện sau mẫu trước, không suy đoán chính xác vị trí trong khoảng. Không khuyên tăng tải trừ khi có quyền kiểm thử và cần điều đó để trả lời câu hỏi của người dùng.
Trong findings, chỉ tạo giả thuyết có kind hypothesis: title nêu khả năng, meaning giải thích thận trọng, nextStep đề xuất cách xác minh an toàn. Tuyệt đối không khẳng định nguyên nhân; không ghi chữ số trong title, meaning và nextStep, trừ thuật ngữ p50 hoặc p95. Không đưa giá trị đo, con số, mốc thời gian, tỷ lệ, HTTP status hay số lượng bằng chữ hoặc chữ số. evidence ghi ngắn gọn là giả thuyết cần kiểm chứng. Không khuyên tăng tải khi chưa xác nhận quyền. Đưa một hoặc hai giả thuyết có thể kiểm chứng dựa trên số liệu, nhưng không khẳng định chúng đúng. Để trống summary, bottlenecks, recommendations và limitations vì hệ thống tự tính nội dung đó.''', request)
    # The model's free-form metrics and observations are not trusted. Only
    # clearly labelled hypotheses without model-authored numeric claims survive.
    hypotheses = []
    for finding in result.findings:
        text = ' '.join((finding.title, finding.meaning, finding.nextStep))
        text = re.sub(r'\bp(?:50|95)\b', '', text, flags=re.IGNORECASE)
        if finding.kind == 'hypothesis' and not any(char.isdigit() for char in text):
            finding.evidence = 'Giả thuyết AI chưa được kiểm chứng; hãy đối chiếu với số liệu và kiểm tra thêm.'
            hypotheses.append(finding)
    first, last = min(request.samples, key=lambda s: s.elapsedSeconds), max(request.samples, key=lambda s: s.elapsedSeconds)
    start, end = f'{first.elapsedSeconds:g} giây', f'{last.elapsedSeconds:g} giây'
    result = AnalyzeLogResponse(summary=summary, bottlenecks=[], recommendations=[],
        findings=[{'kind': 'observation', 'title': 'Số liệu tổng hợp', 'evidence': summary,
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
                   'nextStep': 'Xem các mẫu còn lại để tìm hiểu thêm.'}, *hypotheses],
        limitations=['Giả thuyết AI cần được kiểm chứng; số liệu tổng hợp không xác định nguyên nhân.'],
        analysisMode='model')
    if request.status == 'STOPPED':
        result.limitations.insert(0, 'Bài kiểm thử đã dừng trước khi hoàn thành; số liệu có thể chưa đại diện.')
    elif request.status == 'FAILED':
        result.limitations.insert(0, 'Bài kiểm thử gặp lỗi; số liệu có thể chưa đại diện.')
    return result
