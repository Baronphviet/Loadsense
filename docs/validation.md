# Kết quả kiểm chứng triển khai

Tài liệu này ghi nhận phạm vi kiểm chứng kỹ thuật của LoadSense, gồm mốc baseline **14/10/2026**, đợt nâng cấp **V5** (User Settings, Early Anomaly Detection, NVIDIA Cloud AI, `errorBreakdown`) và đợt hoàn thiện **V6** (Interval Metrics, `p99`/`max`, Load/Stress/Spike Profiles, Scenario Chaining, `stopReason`, Bottleneck Detector, Neutral 3rd-party LLM adapter, Rate Limiting, History Comparison và Benchmark thực nghiệm).

## Tổng hợp các lớp kiểm thử

| Lớp / Công cụ | Phạm vi kiểm tra | Cách chạy | Kết quả ghi nhận mới nhất |
| --- | --- | --- | --- |
| Core Engine (Maven / Java 17) | Unit & integration tests (H2) | `mvn -B -f core-engine/pom.xml test` | **29/29 pass** (`CoreFlowTest`: 20, `MetricAccumulatorTest`: 5, `AiAnalysisInputTest`: 2, `AiClientTest`: 2) |
| PostgreSQL 15 + Flyway V1–V6 | Schema migration `V1`–`V6` & Hibernate `ddl-auto=validate` | Chạy `core-0.0.1-SNAPSHOT.jar` với `postgres:15-alpine` | **6/6 migrations** (`V1`–`V6`) `success = t`, startup khỏe mạnh |
| AI Service (Python 3.11+) | Contract, Bottleneck Detector, Retry & LLM adapter (`unittest`) | `cd ai-service && python3 -m unittest discover -s tests -v` | **24/24 pass** |
| Frontend Unit Tests | Report model, `loadProfileLabels`, `compareExecutions` (`node:test`) | `node --test frontend/src/reportModel.test.js` | **4/4 pass** |
| Frontend Lint & Build | ESLint + Vite production bundle | `pnpm --dir frontend lint && pnpm --dir frontend build` | **Pass** (`eslint` exit 0; Vite `63 modules`) |
| Load & SLA Benchmark | 50 / 100 / 300 VUs tới echo server độc lập, đo STOMP `receivedAt - sampleTime` | `STRICT_SLA=1 VUS_LIST=50,100,300 DURATION_SECONDS=10 RPS_LIMIT=500 node scripts/benchmark.mjs` | **Pass SLA** cả 3 mức (`p95UpdateMs <= 27 ms << 2000 ms`) |
| Browser E2E (Playwright) | Luồng đăng ký, builder, STOMP, polling dự phòng, mock data, AI Workspace, CSV, responsive | `pnpm --dir frontend test:e2e` | Pass ở mốc baseline 14/10/2026 (chưa chạy lại trên UI đợt V6) |

## Phạm vi kiểm thử Core Engine (V1–V6)

- **Xác thực & Chống brute-force:** Đăng ký, username trùng, đăng nhập đúng/sai, API không JWT, và khóa tạm 60 giây (`429 LOGIN_RATE_LIMITED`) khi sai 5 lần liên tiếp trên cùng `username`.
- **Target Policy & Scenario Chaining:** Kiểm tra DTO quá mức, host ngoài allowlist (kể cả multi-host khi lưu chain), port 0, restricted headers, CRLF, cần xác nhận target trước khi chạy; hỗ trợ chuỗi 0–3 `ScenarioStep`, giữ biến `{{var}}` ở path/query/headers/body và chặn `{{...}}` ở scheme/authority để chống SSRF template injection.
- **Load Profiles & Metrics:** `PacedRateLimiter` hỗ trợ `CONSTANT`, `RAMP_UP`, `STEP_STRESS`, `SPIKE`; `MetricAccumulator` duy trì song song histogram tích lũy (`p50`, `p95`, `p99`, `max`) và cửa sổ 1 giây (`intervalP95Ms`, `intervalErrorRate`); phân loại `errorBreakdown` (`http4xx`, `http429`, `http5xx`, `timeout`, `network`); cô lập cookie (`disableCookieManagement`).
- **Early Anomaly Detection & Stop Reason:** Đánh giá theo chỉ số từng nhịp (`intervalErrorRate`, `intervalP95Ms`, bỏ qua nhịp rỗng), cảnh báo qua STOMP và tự dừng bảo vệ sau 3 chu kỳ liên tiếp khi `autoStopOnAnomaly = true`, phân biệt rõ `stopReason` (`COMPLETED`, `USER`, `ANOMALY`, `INTERRUPTED`, `ERROR`).
- **Rate Limit & Recovery:** Giới hạn gọi AI theo người dùng (`ai-rate-limit-per-minute: 10`, sliding window trên `/api/ai/*` và `/api/executions/{id}/report/ai`), bounded AI `ThreadPoolExecutor(2, 4, 60s, LinkedBlockingQueue(32))`, `AiClient` map `401`/`403` từ AI Service sang `503`, `GET /api/executions/{id}/metrics?afterElapsed=`, STOMP ownership và recovery `PENDING/RUNNING` → `FAILED` khi restart.
- **PostgreSQL + Flyway V1–V6:** Kiểm chứng trực tiếp `V1`–`V6` (`V6__interval_metrics_load_profile_and_stop_reason.sql`) trên `postgres:15-alpine` với `hibernate.ddl-auto=validate`.

## Phạm vi kiểm thử AI Service & Frontend

- **AI Service (`ai-service/tests/`):**
  - Adapter hỗ trợ biến môi trường trung lập `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` / `LLM_TIMEOUT_SECONDS` (fallback về `NVIDIA_*`), chỉ đính kèm `chat_template_kwargs` cho Nemotron/NVIDIA URL, retry tối đa 3 lần với exponential backoff khi gặp HTTP `429/502/503/504`.
  - Hàm `detect_bottlenecks_and_recommendations` sinh điểm nghẽn và khuyến nghị bám số liệu (`http429`, `http5xx`, `timeout`/`network`, `http4xx`, độ trễ tăng theo thời gian/nhịp, đuôi `p99`, bão hòa throughput, `stopReason == ANOMALY`), đồng thời lọc câu chứa số liệu chưa kiểm chứng từ LLM bằng `_contains_digits`.
  - Phương án Local LLM / Ollama đã bị loại bỏ khỏi phạm vi triển khai.
- **Frontend (`frontend/src/`):**
  - `TestBuilder.jsx`: chọn `loadProfile` (`CONSTANT`, `RAMP_UP`, `STEP_STRESS`, `SPIKE`), nhập `rampUpSeconds`, thêm 0–3 bước **Scenario Chaining** kèm trích xuất biến JSON và xác thực nhiều target host.
  - `Charts.jsx`: vẽ đồng thời `p50`, `p95`, `p99`, `Interval p95 (1s)` (nét đứt) và `Error %` / `Error % (1s)` (nét đứt).
  - `App.jsx`: `AiReport` hiển thị đầy đủ 3 khối (**Điểm nghẽn**, **Đề xuất khắc phục**, **Quan sát & Giả thuyết**); `ExecutionDetail` hiển thị `p99`/`Max`, nhãn `loadProfile`, nhãn `TỰ DỪNG BẢO VỆ` khi `stopReason === 'ANOMALY'`; trang Lịch sử bổ sung `ComparePanel` so sánh **Baseline vs Current**.

## Kết quả Benchmark tải & độ trễ realtime (`scripts/benchmark.mjs`)

Thực nghiệm chạy trên `core-0.0.1-SNAPSHOT.jar` (Java 17) kết nối PostgreSQL 15, bắn tải tới Node.js HTTP echo server **độc lập** trên `127.0.0.1:18081` (độ trễ phản hồi giả lập `15 ms`), với `DURATION_SECONDS=10`, `RPS_LIMIT=500`, `STRICT_SLA=1` (ngưỡng SLA độ trễ cập nhật STOMP `<= 2000 ms`):

| Mức tải (VUs) | Tổng Request | Lỗi | Tỷ lệ lỗi | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Số mẫu STOMP | Độ trễ STOMP TB (ms) | Độ trễ STOMP p95 (ms) | Độ trễ STOMP Max (ms) | SLA `< 2000 ms` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **50 VUs** | 4.955 | 0 | 0,00% | 495,36 | 16 | 17 | 43 | 10 | 17 | 27 | 27 | **Đạt** |
| **100 VUs** | 4.988 | 1 | 0,02% | 498,74 | 16 | 16 | 17 | 10 | 13 | 19 | 19 | **Đạt** |
| **300 VUs** | 4.992 | 3 | 0,06% | 499,13 | 15 | 16 | 16 | 10 | 11 | 16 | 16 | **Đạt** |

Nhận xét: Động cơ điều phối **300 VUs** ổn định ở ~**499 req/s** sát trần `RPS_LIMIT=500`, độ trễ cập nhật WebSocket STOMP `p95` chỉ từ **16–27 ms** (thấp hơn rất nhiều so với ngưỡng `1.000–2.000 ms` trong đề cương).

## Bảng kiểm thử hộp đen (Black-box Test Cases theo Use Case)

| Mã TC | Use Case | Đầu vào | Các bước thực hiện | Kết quả kỳ vọng | Kết quả thực tế |
| --- | --- | --- | --- | --- | --- |
| **TC-01** | UC-01 Đăng ký / Đăng nhập & Bảo mật | Username hợp lệ; mật khẩu sai 5 lần liên tiếp | 1. Đăng ký tài khoản mới.<br>2. Gửi `POST /api/auth/login` sai mật khẩu 5 lần trong 60s.<br>3. Thử đăng nhập lần 6. | Lần 1–5 trả `401`; lần 6 bị khóa tạm trả `429 LOGIN_RATE_LIMITED`; truy cập tài nguyên user khác trả `404`/`403`. | **Đạt** (`CoreFlowTest`) |
| **TC-02** | UC-02 Cấu hình tải & Profile | `targetUrl` trong allowlist, `loadProfile = RAMP_UP`, `rampUpSeconds = 10`, `virtualUsers = 50` | 1. Tạo config qua `POST /api/configs`.<br>2. Thử cấu hình chứa host ngoài allowlist hoặc header `Host`/`CRLF`. | Config hợp lệ lưu được với `loadProfile` và `rampUpSeconds`; config vi phạm allowlist/header bị chặn `400`. | **Đạt** (`CoreFlowTest`) |
| **TC-03** | UC-02b Scenario Chaining | Bước 1: `GET /demo/echo` trích xuất `token`; Bước 2: `POST /api/target?t={{token}}` | 1. Tạo config có `steps` (2 bước) và xác nhận host.<br>2. Thử chèn `{{host}}` vào scheme/authority URL.<br>3. Chạy execution. | `TargetPolicy` chặn template ở host/scheme; bước 2 nhận được giá trị `token` trích từ JSON bước 1 và không rò rỉ sang iteration khác. | **Đạt** (`CoreFlowTest`) |
| **TC-04** | UC-03 Mock Data & Schema | JSON Schema hợp lệ + mảng payload đúng/sai schema | 1. Tạo `MockDataSet` qua `POST /api/configs/{id}/mock-data` với payload đúng schema.<br>2. Thử lưu payload thiếu trường bắt buộc. | Payload hợp lệ được lưu và xoay vòng khi chạy (`mockDataSetId` gắn trên execution); payload sai schema trả `400`. | **Đạt** (`CoreFlowTest`) |
| **TC-05** | UC-04 Thực thi, Early Anomaly & Stop Reason | Target chậm / trả `500`, bật `earlyAnomalyEnabled=true`, `autoStopOnAnomaly=true` | 1. Xác nhận target và start execution.<br>2. Quan sát 3 nhịp đo có `intervalErrorRate` hoặc `intervalP95Ms` vượt ngưỡng.<br>3. Kiểm tra dừng thủ công trên bài test khác. | Hệ thống phát `anomalyWarning` qua STOMP, tự dừng sau 3 chu kỳ bất thường với `stopReason = ANOMALY`; dừng tay ghi `stopReason = USER`. | **Đạt** (`CoreFlowTest`) |
| **TC-06** | UC-04b Tải 300 VUs & STOMP SLA | Target echo `15ms`, 50/100/300 VUs, 10s, 500 RPS | 1. Chạy `node scripts/benchmark.mjs` kết nối STOMP `/user/queue/executions/{id}`.<br>2. Đo `receivedAt - sampleTime`. | Engine hoàn tất cả 3 mức tải; tỷ lệ lỗi < 0,1%; độ trễ cập nhật STOMP p95 < 2000 ms. | **Đạt** (`p95UpdateMs = 16–27 ms`) |
| **TC-07** | UC-05 Lịch sử, Báo cáo, CSV & So sánh | 2 execution đã hoàn tất (`Baseline`, `Current`) | 1. Mở lịch sử, lọc theo tên/trạng thái.<br>2. Xuất CSV.<br>3. Chọn 2 execution để đối chiếu. | CSV có đủ cột `p99_latency_ms`, `max_latency_ms`, `interval_p95_ms`, `interval_error_rate`; UI tính đúng chênh lệch `Throughput`, `p95`, `p99`, `Error Rate`. | **Đạt** (`CoreFlowTest`, `reportModel.test.js`) |
| **TC-08** | UC-AI Prompt-to-Test, Mock Data & AI Explainer | Prompt tiếng Việt, JSON Schema, hoặc log execution có `http429`/`http5xx`/`p99` cao | 1. Gọi `/api/ai/parse-prompt` và `/api/ai/generate-mockdata`.<br>2. Gọi AI Explainer > 10 lần/phút/user.<br>3. Gọi `/analyze-log` với lỗi `429` và spike `intervalP95Ms`. | Lượt gọi thứ 11 trả `429 AI_RATE_LIMITED`; báo cáo AI trả đủ `bottlenecks`, `recommendations`, `findings`; lỗi HTTP 429/503 từ LLM có retry/backoff. | **Đạt** (`CoreFlowTest`, `test_ai_features.py`) |

## Mốc kiểm chứng baseline (14/10/2026)

Trên bản baseline trước khi tích hợp NVIDIA Cloud AI, V5 và V6, toàn bộ 14 backend tests (H2 & PostgreSQL 15), Flyway V1+V2, ESLint, Vite build, Docker Compose và Playwright E2E đã pass trên môi trường cục bộ (`loadsense-validation`). Đối với CI (`.github/workflows/ci.yml`), workflow chạy 3 job: `core-engine`, `frontend` (`node --test`, `lint`, `build`) và `ai-contract` (`unittest`).
