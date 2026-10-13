# REST và WebSocket contract

Core URL local `http://localhost:8080`; dev frontend proxy giữ cùng origin. REST JSON camelCase. API được mô tả trong [openapi.json](openapi.json).

## Authentication

`POST /api/auth/register` (201), `POST /api/auth/login` (200):

```json
{"username":"developer","password":"your-password-at-least-8-chars"}
```

Response: `token`, `username`, `userId`, `expiresAt`. Các API còn lại cần `Authorization: Bearer <token>`, trừ health và demo. JWT HS256, issuer loadsense, subject UUID user. Không có refresh token; hết 24h phải đăng nhập lại.

## Cấu hình và dữ liệu

| Method | Path | Kết quả |
| --- | --- | --- |
| GET | `/api/test-configs` | 200, tất cả cấu hình thuộc user |
| GET | `/api/test-configs/{id}` | 200, cấu hình đầy đủ thuộc user |
| POST | `/api/test-configs` | 201, tạo mới |
| PUT | `/api/test-configs/{id}` | 200, sửa mới toàn bộ input |
| DELETE | `/api/test-configs/{id}` | 204, chỉ xóa khi chưa có lịch sử |
| GET | `/api/test-configs/{id}/mock-data` | 200, bộ dữ liệu thuộc cấu hình |
| POST | `/api/test-configs/{id}/mock-data` | 201, thêm bộ JSON thủ công |

Input cấu hình:

```json
{
  "name": "Products API",
  "targetUrl": "http://localhost:8080/demo/echo?delayMs=30",
  "httpMethod": "GET",
  "headers": {},
  "body": "",
  "virtualUsers": 20,
  "durationSeconds": 30,
  "requestsPerSecond": 100,
  "timeoutMs": 5000
}
```

Response bổ sung `id`, `createdAt`, `updatedAt`. GET/HEAD không nhận body. Phương thức khác có thể dùng JSON hoặc văn bản; đặt Content-Type phù hợp trong headers. URL phải thuộc exact allowlist ở server. Sửa cấu hình đang chạy không thay snapshot execution.

Mock input (chỉ method có body):

```json
{
  "name": "Product samples",
  "schemaJson": "{\"type\":\"object\"}",
  "payloadsJson": "[{\"id\":1},{\"id\":2}]"
}
```

`schemaJson` nullable; chỉ kiểm tra JSON object, chưa validate payload theo JSON Schema. `payloadsJson` là chuỗi chứa mảng JSON object, 1–1000 phần tử. Response bổ sung `id`, `testConfigId`, `createdAt`. Bộ dữ liệu dùng đúng config và owner; chọn một ID của config khác trả 404.

## Execution

| Method | Path | Kết quả |
| --- | --- | --- |
| POST | `/api/test-configs/{id}/executions` | 202, execution PENDING |
| POST | `/api/executions/{id}/stop` | 200, gửi stop idempotent |
| GET | `/api/executions` | 200, 100 lần gần nhất thuộc user |
| GET | `/api/executions/{id}` | 200, snapshot metadata + summary |
| GET | `/api/executions/{id}/metrics` | 200, toàn bộ sample theo thời gian |
| GET | `/api/executions/{id}/report` | 200 sau terminal; 409 khi chưa kết thúc |
| GET | `/api/executions/{id}/metrics.csv` | 200, CSV + attachment filename |

Start input:

```json
{"authorizedTarget":true,"mockDataSetId":null}
```

`authorizedTarget` bắt buộc true. Khi capacity đầy trả 409; không tạo execution để queue. Có thể chạy nhiều lần cùng cấu hình nếu còn slot.

Execution response fields: `id`, `testConfigId`, `name`, `targetUrl`, `httpMethod`, `virtualUsers`, `durationSeconds`, `requestsPerSecond`, `status`, `startTime`, `endTime`, `createdAt`, `failureReason`, `totalRequests`, `failedRequests`, `avgLatencyMs`, `p50LatencyMs`, `p95LatencyMs`, `throughput`, `errorRate`. Không trả config_snapshot/headers/body.

Vòng đời: `PENDING → RUNNING → COMPLETED | STOPPED | FAILED`; stop trước khi worker chạy có thể chuyển `PENDING → STOPPED`. Stop response có thể vẫn PENDING/RUNNING trong lúc cleanup; chờ polling/event terminal. Request lỗi không khiến execution FAILED: vẫn COMPLETED với failedRequests/errorRate. FAILED dành cho engine/storage/restart.

Report fields: `id`, `executionId`, `summary`, `assessment`, `aiStatus` (`DEFERRED` hoặc `COMPLETED`), `aiAnalysis` (chuỗi JSON hoặc null), `createdAt`. Assessment: `ENGINE_FAILED`, `ENGINE_INTERRUPTED`, `NO_SAMPLES`, `HIGH_ERROR_RATE`, `HIGH_LATENCY`, `WITHIN_DEFAULT_THRESHOLDS`.

## WebSocket / STOMP

1. Kết nối native WebSocket `ws://localhost:8080/ws` (hoặc `wss` khi HTTPS).
2. STOMP CONNECT header `Authorization: Bearer <token>`; token không đặt trong URL.
3. SUBSCRIBE `/user/queue/executions/{id}`. Server xác thực token và kiểm tra owner. Không subscribe `/queue/...` trực tiếp.
4. Server phát event khoảng 1 giây và khi kết thúc:

```json
{
  "execution": { "id": "uuid", "status": "RUNNING", "totalRequests": 100 },
  "metric": {
    "id": "uuid",
    "sampleTime": "2026-10-14T00:00:00Z",
    "elapsedSeconds": 1.02,
    "totalRequests": 100,
    "failedRequests": 2,
    "throughput": 98.03,
    "avgLatencyMs": 40.2,
    "p50LatencyMs": 38,
    "p95LatencyMs": 65,
    "errorRate": 2,
    "activeUsers": 20
  }
}
```

`execution` trong ví dụ được rút gọn; event thật dùng đầy đủ ExecutionView. `metric=null` ở sự kiện bắt đầu. `activeUsers=0` ở sample cuối. Throughput metric tính theo interval; percentiles/errorRate tích lũy. Lấy REST metrics để phục hồi mẫu thiếu; merge theo UUID. Không cho client STOMP SEND.

Frontend reconnect mỗi 3 giây, polling 2 giây và tắt kết nối khi terminal. Broker in-memory không lưu message cho subscriber offline; database là nguồn bền vững.

## System / AI contracts

- `GET /api/health`: public, trả trạng thái Core và cấu hình AI. Process readiness; Docker chỉ start frontend sau khi Core khởi động và Flyway/JPA hoàn thành.
- `GET /api/capabilities`: auth, aiEnabled phản ánh AI Service đã cấu hình, allowedTargetHosts, maxVirtualUsers, maxDurationSeconds, maxRequestsPerSecond, activeExecutions, demoEnabled.
- `POST /api/target-hosts`: auth; body `{"host":"api.example.com","authorized":true}` xác nhận một host Internet công khai cho tài khoản hiện tại. Host nội bộ vẫn cần được quản trị viên cấu hình qua `ALLOWED_TARGET_HOSTS`; danh sách được lưu và sử dụng lại.
- `POST /api/ai/parse-prompt`, `/api/ai/generate-mockdata`, `/api/ai/analyze-log`: yêu cầu JWT; Core chuyển tiếp tới AI Service. Prompt và mock data dùng NVIDIA và cần được người dùng duyệt; phân tích dùng model khi đủ dữ liệu.
- `POST /api/executions/{id}/report/ai`: yêu cầu JWT và quyền sở hữu execution đã kết thúc; gửi thông số tải từ snapshot, metrics tổng hợp và tối đa 60 mẫu rải đều (bao gồm đầu/cuối) tới AI Service, lưu và thay thế phân tích AI của report. Dữ liệu phân tích không chứa URL, tên, method, header, body, token người dùng, failure reason, timestamp hoặc log thô. Các mẫu có `elapsedSeconds`, `totalRequests`, `failedRequests`, `throughput`, `avgLatencyMs`, `p50LatencyMs`, `p95LatencyMs`, `errorRate`, `activeUsers`; throughput tính theo khoảng trước mẫu, số request/lỗi, latency và errorRate tích lũy từ đầu, còn activeUsers là số người dùng đang hoạt động tại mẫu.
- Report `aiAnalysis` là chuỗi JSON chứa `summary`, `bottlenecks`, `recommendations`, `findings`, `limitations` và `analysisMode`. Mỗi finding gồm `kind` (`observation` hoặc `hypothesis`), `title`, `evidence`, `meaning`, `nextStep`. Nếu có dưới 10 request, hoặc các mẫu không có ít nhất 3 giá trị `elapsedSeconds` khác nhau và 3 giá trị `totalRequests` khác nhau, `analysisMode="insufficient_data"`: AI Service tạo tóm tắt xác định từ số liệu và nêu giới hạn, không gọi model, không đưa ra findings. Trường hợp khác trả `analysisMode="model"`: tóm tắt và quan sát được tạo xác định từ input, các giả thuyết do model đề xuất được đánh dấu cần kiểm chứng. Báo cáo đã lưu trước đây có thể chỉ chứa ba trường đầu; chúng không bị thay đổi cho tới khi yêu cầu phân tích lại.
- Request `/api/ai/analyze-log` có bảy metrics tổng hợp bắt buộc; `status`, `loadConfig` (`virtualUsers`, `durationSeconds`, `requestsPerSecond`, `timeoutMs`) và `samples` tối đa 60 mẫu là tùy chọn. Không đưa dữ liệu nhạy cảm vào request.
- FastAPI `/parse-prompt`, `/generate-mockdata`, `/validate-mockdata`, `/analyze-log` yêu cầu header `X-AI-Service-Token`; `/health` chỉ phản ánh cấu hình, không gọi NVIDIA. Chưa tích hợp Ollama.

## Lỗi và giới hạn

```json
{"code":"INVALID_INPUT","message":"Host API chưa nằm trong ALLOWED_TARGET_HOSTS","fields":{}}
```

| Status | Ý nghĩa |
| --- | --- |
| 400 | DTO/JSON/URL/header/body/load không hợp lệ; fields mô tả lỗi Bean Validation |
| 401 | Sai credentials hoặc JWT thiếu/hết hạn; resource-server dùng chuẩn Bearer challenge |
| 404 | Không tồn tại hoặc không thuộc user (không tiết lộ dữ liệu người khác) |
| 409 | Username trùng, engine đầy, cấu hình có lịch sử, report chưa sẵn sàng |
| 502 | Phản hồi từ model không hợp lệ hoặc dịch vụ AI gặp lỗi upstream |
| 503 | Dịch vụ AI chưa được cấu hình hoặc không khả dụng |
| 504 | Dịch vụ AI hết thời gian chờ |

1–300 VUs, 1–600s, 1–500 RPS, 100–30000ms timeout; headers 32/16KiB, body 64KiB. Headers nhạy cảm chỉ có trong cấu hình của chính user; không nằm trong execution summary/metric/report/export.
