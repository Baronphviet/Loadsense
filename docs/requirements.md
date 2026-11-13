# Đối chiếu yêu cầu đề cương

Nguồn: đề cương nhóm 6, “Phát triển Hệ thống kiểm thử hiệu năng Web API tích hợp AI”, Học viện Kỹ thuật Mật mã, 2026. Theo chỉ đạo triển khai hiện tại, phương án **Local LLM / Ollama đã được loại bỏ** và thay bằng **API LLM bên thứ ba tương thích chuẩn OpenAI** (mặc định **NVIDIA Cloud AI — NVIDIA Nemotron**, cấu hình trung lập qua biến môi trường `LLM_*` / `NVIDIA_*`).

| Yêu cầu | Hiện thực / nơi kiểm tra | Trạng thái |
| --- | --- | --- |
| Boundary – Control – Entity | `controller`/`dto`, `service`/`client`, `entity`/`repository`; `docs/architecture.md` | Đã triển khai |
| Spring Boot REST và JWT | `SecurityConfig`, `AuthService` (khóa tạm 60s sau 5 lần sai mật khẩu), `AuthController`, `/api/settings` | Đã triển khai |
| PostgreSQL | PostgreSQL 15, Flyway `V1`–`V6`, 6 entity chính | Đã triển khai PostgreSQL |
| User/TestConfig/MockDataSet/TestExecution/TestMetric/TestReport | 6 bảng, FK/index/constraints, ORM, V5 settings/traceability & V6 profile/chain/interval/`p99`/`stopReason` | Đã triển khai |
| Cấu hình không viết script & Profile tải | `TestBuilder`: URL/method/headers/body/VUs/duration/RPS/timeout + `loadProfile` (`CONSTANT`, `RAMP_UP`, `STEP_STRESS`, `SPIKE`) + `rampUpSeconds` | Đã triển khai |
| Scenario Chaining (tối giản) | Cấu hình chuỗi 0–3 `ScenarioStep`, trích xuất biến JSON (`{{var}}`) sang bước sau; `TargetPolicy` chặn template trong scheme/authority | Đã triển khai |
| Prompt-to-Test bằng AI | AI Workspace + Core `/api/ai/parse-prompt` + API LLM bên thứ ba (có rate limit 10 lượt/phút/user) | Đã triển khai |
| Smart Mock-Data & kiểm định schema | `/api/ai/generate-mockdata` + kiểm định JSON Schema ở Core + round-robin | Đã triển khai |
| ExecutorService đa luồng | `LoadEngine` bounded VUs/coordinators, `PacedRateLimiter` theo `loadProfile`, `disableCookieManagement` | Đã triển khai |
| Dashboard realtime latency/error rate | Chart.js (`p50`, `p95`, `p99`, `Interval p95`, `Error %`), `errorBreakdown`, private STOMP + `afterElapsed` polling | Đã triển khai |
| Early Anomaly Detection & Stop Reason | Cảnh báo sớm dựa trên `intervalErrorRate` / `intervalP95Ms` + tự dừng bảo vệ sau 3 nhịp (`stopReason = ANOMALY`) | Đã triển khai |
| Dừng test thủ công | Stop API idempotent, interrupt workers, cleanup/report (`stopReason = USER`) | Đã triển khai |
| Lịch sử, báo cáo & So sánh 2 lần chạy | 100 execution gần nhất, filter/search, CSV (kèm `p99`, `max`, `interval_*`) và bảng đối chiếu **Baseline vs Current** | Đã triển khai |
| AI Explainer & Bottleneck Detector | `/api/ai/analyze-log`, `detect_bottlenecks_and_recommendations` sinh đầy đủ `bottlenecks`, `recommendations`, `findings`; Auto AI Explainer (`ThreadPoolExecutor` hữu hạn) | Đã triển khai |
| Frontend workspace / settings / dashboard / history | AI Workspace / `⚙ Cài đặt` / Tổng quan / Lịch sử & báo cáo | Đã triển khai |
| JSON Schema chuẩn hóa đầu ra model & Retry | Pydantic schemas + `response_format` JSON Schema + retry/backoff HTTP `429/502/503/504` | Đã triển khai |
| Vài trăm luồng đồng thời & SLA 1–2s | Benchmark thực nghiệm 50/100/300 VUs đạt `~495–499 req/s`, STOMP `p95UpdateMs = 16–27 ms` | Đã kiểm chứng thực nghiệm |
| Use Case / Entity / Package UML & Kiểm thử hộp đen | Sơ đồ BCE/Sequence/ER/Class trong `docs/architecture.md`; bảng Black-box Test Cases (`TC-01`–`TC-08`) trong `docs/validation.md` | Đã cập nhật |
| Cài đặt / triển khai | Dockerfiles, Compose (`--profile ai`), nginx proxy, README | Đã chuẩn bị |

## Điều kiện nghiệm thu của phiên bản hiện tại (V6)

1. Đăng ký/đăng nhập (có chống brute-force `429 LOGIN_RATE_LIMITED`), quản lý cài đặt người dùng (`GET/PUT /api/settings`) và tạo cấu hình bằng biểu mẫu (`CONSTANT`/`RAMP_UP`/`STEP_STRESS`/`SPIKE`, chuỗi tối đa 3 bước) hoặc Prompt-to-Test.
2. Sinh dữ liệu mock thủ công hoặc bằng Smart Mock-Data; Core kiểm định payload theo JSON Schema trước khi lưu.
3. Bắt đầu lần chạy sau xác nhận target; engine điều phối theo `loadProfile`, tách riêng cookie/iteration, ghi nhận `p99`/`max`/`intervalP95Ms`/`intervalErrorRate`, phân loại `errorBreakdown`, phát hiện bất thường sớm trên nhịp đo và ghi rõ `stopReason` (`COMPLETED`, `USER`, `ANOMALY`, `INTERRUPTED`, `ERROR`).
4. Dashboard nhận metrics qua STOMP (đã đo thực tế `p95UpdateMs = 16–27 ms << 2000 ms` ở 50/100/300 VUs), tự chuyển incremental polling (`afterElapsed`) khi gián đoạn.
5. Khi execution kết thúc, Auto AI Explainer (nếu bật) tự chạy nền với giới hạn tốc độ per-user và trả về đầy đủ `bottlenecks`, `recommendations` và `findings`.
6. Trang Lịch sử & báo cáo hỗ trợ xuất CSV đầy đủ cột mới và so sánh **Baseline vs Current** giữa 2 lần chạy; mọi REST/STOMP kiểm tra chặt chẽ quyền sở hữu theo user.

## Hướng dẫn chỉnh sửa câu chữ Đề cương / Báo cáo thực tập

Khi trình bày đồ án/báo cáo thực tập theo chỉ đạo mới nhất, cần điều chỉnh 3 điểm câu chữ sau cho đồng bộ với hệ thống thực tế:

1. **Thay thế Local LLM (Ollama) bằng API LLM bên thứ ba:**
   - Trong mục *Phạm vi AI / Công nghệ sử dụng*: thay *"tích hợp Local LLM thông qua Ollama"* bằng *"tích hợp API LLM bên thứ ba tương thích chuẩn OpenAI (mặc định NVIDIA Cloud AI — NVIDIA Nemotron, cho phép cấu hình linh hoạt qua `LLM_BASE_URL` / `LLM_MODEL`)"*.
   - Nêu rõ giải pháp kiểm soát chi phí/quota & độ tin cậy: giới hạn 10 lượt/phút/người dùng ở Core (`RateLimiter`), hàng đợi AI hữu hạn, cơ chế `INSUFFICIENT_DATA` không gọi model khi dữ liệu thưa, và retry/backoff tự động khi gặp HTTP `429`/`503`.
2. **Giao diện Frontend (HTML/JavaScript + Chart.js):**
   - Ghi rõ *"Frontend xây dựng trên HTML/CSS/JavaScript sử dụng React để tổ chức component và quản lý trạng thái realtime, kết hợp Chart.js để trực quan hóa biểu đồ hiệu năng."*
3. **Scenario Chaining:**
   - Đề cương gốc ghi Scenario Chaining ở mức mở rộng; trong phiên bản V6, hệ thống đã triển khai **bản tối giản (2–3 bước tuần tự với trích xuất biến JSON `{{var}}`)**, còn chuỗi điều kiện rẽ nhánh phức tạp và distributed workers giữ ở mục *Định hướng phát triển tiếp theo*.

## Hướng mở rộng sau nghiệm thu

Distributed load workers (nhiều node phát tải), mở rộng Scenario Chaining hỗ trợ vòng lặp/rẽ nhánh điều kiện, phân trang đầy đủ sau 100 execution và chính sách dọn dẹp dữ liệu theo thời gian (retention).
