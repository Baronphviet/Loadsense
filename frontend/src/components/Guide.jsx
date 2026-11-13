import { useEffect } from 'react'
import './Guide.css'

const terms = [
  ['api', 'API', 'Cách các phần mềm trao đổi dữ liệu. Ví dụ: ứng dụng lấy danh sách sản phẩm từ một dịch vụ.'],
  ['endpoint', 'Endpoint', 'Địa chỉ cụ thể của một chức năng API, ví dụ /demo/echo.'],
  ['method', 'Method', 'Hành động gửi tới API: GET thường đọc dữ liệu, POST thường gửi dữ liệu mới.'],
  ['header', 'Header', 'Thông tin đi kèm request, ví dụ Content-Type: application/json. Không chia sẻ token hay mật khẩu.'],
  ['body', 'Body', 'Nội dung gửi trong request, thường là JSON. GET và HEAD trong LoadSense không dùng body.'],
  ['schema', 'JSON Schema', 'Bản mô tả cấu trúc dữ liệu, ví dụ yêu cầu trường name là chuỗi.'],
  ['virtual-users', 'Virtual users (người dùng ảo)', 'Số worker mô phỏng gửi request đồng thời; không phải số người thật.'],
  ['rps', 'RPS', 'Số request mỗi giây. Giới hạn RPS là số request tối đa được bắt đầu mỗi giây cho toàn bài test.'],
  ['timeout', 'Timeout', 'Thời gian chờ tối đa cho một request. Quá thời gian này, request được tính là lỗi.'],
  ['throughput', 'Throughput', 'Số request hoàn tất mỗi giây thực tế. Chỉ số này có thể thấp hơn giới hạn RPS.'],
  ['p50', 'p50', 'Độ trễ tại phân vị 50: khoảng một nửa request có thời gian phản hồi bằng hoặc thấp hơn mức này.'],
  ['p95', 'p95', 'Độ trễ tại phân vị 95: khoảng 95% request có thời gian phản hồi bằng hoặc thấp hơn mức này.'],
  ['error-rate', 'Tỷ lệ lỗi', 'Phần trăm request thất bại trên tổng request đã hoàn tất. Hãy xem cùng số request để hiểu mức độ tin cậy.'],
]

const sections = [
  ['getting-started', 'Bài test đầu tiên'], ['prompt', 'Tạo với AI'], ['mock-data', 'Dữ liệu mẫu'],
  ['run', 'Chạy và dừng'], ['results', 'Đọc kết quả'], ['troubleshooting', 'Xử lý lỗi'], ['glossary', 'Thuật ngữ'],
]

export default function Guide({ initialSection }) {
  useEffect(() => {
    if (!initialSection) return
    const target = document.getElementById(`guide-${initialSection}`)
    if (target?.tagName === 'DETAILS') target.open = true
    target?.scrollIntoView({ block: 'start' })
  }, [initialSection])

  return <div className="guide">
    <header className="guide-hero">
      <span className="guide-kicker">TÀI LIỆU LOADSENSE</span>
      <h1>Bắt đầu kiểm thử, từng bước một.</h1>
      <p>Chưa quen với kiểm thử hiệu năng? Hãy bắt đầu từ một bài thử nhỏ, rồi khám phá các chỉ số khi bạn cần.</p>
      <a className="guide-hero-link" href="#guide-getting-started">Bắt đầu bài test đầu tiên <span aria-hidden="true">↗</span></a>
    </header>
    <nav className="guide-nav" aria-label="Mục lục tài liệu">{sections.map(([id, name]) => <a key={id} href={`#guide-${id}`}>{name}</a>)}</nav>
    <div className="guide-content">
      <section className="guide-section" id="guide-getting-started" tabIndex={-1}>
        <span className="guide-section-number">01 / BẮT ĐẦU</span><h2>Bài test đầu tiên</h2>
        <p>Thử với API demo có sẵn trong LoadSense. Chỉ kiểm thử API mà bạn có quyền sử dụng và bắt đầu với tải thấp.</p>
        <ol className="guide-steps">
          <li><strong>Quét URL tự động hoặc tạo cấu hình tải.</strong> Vào <b>Auto Test Suite</b> dán URL web/API để crawl đa tầng (tối đa 100 Test Cases), hoặc vào <b>Kiểm thử chịu tải → ＋ Tạo cấu hình tải</b>. Nhập URL <code>http://core-engine:8080/demo/echo</code>.</li>
          <li><strong>Đặt tải nhỏ.</strong> Chọn 1 người dùng ảo, thời lượng 10 giây, giới hạn 1 RPS và timeout 5000 ms. Lưu cấu hình.</li>
          <li><strong>Chạy bài test.</strong> Chọn <b>Chạy test</b>, đọc và xác nhận quyền kiểm thử, rồi chọn <b>Bắt đầu kiểm thử</b>.</li>
          <li><strong>Xem kết quả.</strong> Theo dõi biểu đồ WebSocket ngay tại <b>Kiểm thử chịu tải</b>, sau đó xem lại & so sánh tại <b>Lịch sử & báo cáo</b>.</li>
        </ol>
        <div className="guide-callout"><strong>Chưa biết endpoint?</strong> AI có thể đề xuất, nhưng có thể đoán sai. Hãy kiểm tra tài liệu của API và quyền kiểm thử trước khi chạy.</div>
      </section>
      <section className="guide-section" id="guide-prompt" tabIndex={-1}>
        <span className="guide-section-number">02 / AI PROMPT-TO-TEST</span><h2>Tạo cấu hình bằng lời mô tả</h2>
        <p>Ở thanh <b>AI Prompt-to-Test</b> trên đầu mục <b>Kiểm thử chịu tải</b>, mô tả URL, method và mức tải mong muốn. Ví dụ:</p>
        <blockquote>Kiểm thử GET http://core-engine:8080/demo/echo với 1 người dùng trong 10 giây, tối đa 1 request mỗi giây, timeout 5000 ms.</blockquote>
        <p>Chọn <b>Sinh cấu hình AI</b>, kiểm tra cảnh báo và thông số, rồi chọn <b>Duyệt và chỉnh sửa</b> trước khi lưu. Prompt được gửi tới nhà cung cấp AI đã cấu hình: không nhập mật khẩu, token hay dữ liệu bí mật.</p>
      </section>
      <section className="guide-section" id="guide-mock-data" tabIndex={-1}>
        <span className="guide-section-number">03 / DỮ LIỆU</span><h2>Sinh dữ liệu mẫu</h2>
        <p>Với cấu hình POST, PUT, PATCH hoặc DELETE, chọn <b>Dữ liệu</b>. Nhập tên bộ dữ liệu, JSON Schema và số payload, rồi chọn <b>Sinh và xem trước bằng AI</b>. Schema được gửi tới NVIDIA, vì vậy không đưa bí mật vào đó.</p>
        <pre><code>{'{\n  "type": "object",\n  "properties": { "name": { "type": "string" } },\n  "required": ["name"]\n}'}</code></pre>
        <p>Xem và sửa mảng payload trước khi chọn <b>Lưu bộ dữ liệu</b>. Khi chạy, chọn dataset để gửi luân phiên; GET và HEAD không dùng body.</p>
      </section>
      <section className="guide-section" id="guide-run" tabIndex={-1}>
        <span className="guide-section-number">04 / THỰC HÀNH</span><h2>Chạy và dừng test</h2>
        <p>Chọn <b>Chạy test</b> trên cấu hình trong mục <b>Kiểm thử chịu tải</b>, chọn dữ liệu nếu cần và xác nhận quyền kiểm thử trước mỗi lần chạy. Biểu đồ realtime sẽ hiển thị ngay phía trên. Nếu cần dừng sớm, chọn <b>Dừng test</b>.</p>
        <p>Với host công khai mới, xác nhận quyền kiểm thử trong biểu mẫu cấu hình trước khi lưu. Host nội bộ vẫn cần được quản trị viên cho phép.</p>
      </section>
      <section className="guide-section" id="guide-results" tabIndex={-1}>
        <span className="guide-section-number">05 / KẾT QUẢ</span><h2>Đọc báo cáo</h2>
        <p>Trước tiên xem tổng request và tỷ lệ lỗi, sau đó xem throughput, p50 và p95. So sánh với mục tiêu của bạn và với các lần chạy có điều kiện tương tự; không có một con số tốt cho mọi hệ thống.</p>
        <p>Chọn <b>Phân tích bằng AI</b> sau khi bài test kết thúc để nhận gợi ý dựa trên số liệu. AI có thể nêu giả thuyết, nhưng không tự biết nguyên nhân bên trong server. Mở lại kết quả trong <b>Lịch sử & báo cáo</b>.</p>
      </section>
      <section className="guide-section" id="guide-troubleshooting" tabIndex={-1}>
        <span className="guide-section-number">06 / KHI CẦN GIÚP ĐỠ</span><h2>Xử lý lỗi thường gặp</h2>
        <div className="guide-faq">
          <details><summary>Host chưa được xác nhận quyền kiểm thử</summary><p>Kiểm tra URL, rồi đánh dấu xác nhận quyền kiểm thử trong biểu mẫu và lưu. Chỉ xác nhận nếu bạn có quyền. Host nội bộ cần quản trị viên cho phép.</p></details>
          <details><summary>Không có request hoặc nhiều request lỗi</summary><p>Kiểm tra URL, method, quyền truy cập và timeout. Nếu test dừng sớm hoặc chưa có request hoàn tất, hãy chạy lại với tải thấp và đọc thông báo lỗi.</p></details>
          <details><summary>Không tạo được đề xuất hoặc dữ liệu AI</summary><p>Đọc thông báo và thử lại. Nếu lỗi tiếp diễn, nhờ người vận hành kiểm tra cấu hình và kết nối AI Service; bạn vẫn có thể tạo cấu hình và nhập dữ liệu thủ công.</p></details>
          <details><summary>Không lưu được dữ liệu mẫu</summary><p>Kiểm tra payload là mảng JSON object và mỗi object thỏa JSON Schema đã nhập. GET và HEAD không hỗ trợ body.</p></details>
        </div>
      </section>
      <section className="guide-section" id="guide-glossary" tabIndex={-1}>
        <span className="guide-section-number">07 / TRA CỨU</span><h2>Thuật ngữ dễ hiểu</h2><p>Chọn một thuật ngữ để xem giải thích ngắn.</p>
        <div className="guide-glossary">{terms.map(([id, title, description]) => <details id={`guide-${id}`} key={id}><summary>{title}</summary><p>{description}</p></details>)}</div>
      </section>
    </div>
  </div>
}
