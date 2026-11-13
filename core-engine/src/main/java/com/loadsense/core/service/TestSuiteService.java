package com.loadsense.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.client.AiClient;
import com.loadsense.core.client.TargetHttpClient;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.TestSuite;
import com.loadsense.core.repository.TestSuiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ Auto Test Suite theo mô hình TestSprite:
 * - Deep Multi-Level BFS Crawler (3 tầng: Home -> Chuyên mục -> Trang con/Bài viết), sinh tới 100 Test Cases.
 * - Lưu cây phân nhánh (visitedPages, parentPath, depth, actionLabel) + HTML snapshot đã làm sạch (<base href>).
 * - Đồng bộ 2 chiều Form ↔ YAML, thực thi song song 8 worker và sinh chẩn đoán Error -> Cause -> Fix.
 */
@Service
public class TestSuiteService {
    private static final int CRAWL_TIMEOUT_MS = 4000;
    private static final int RUN_TIMEOUT_MS = 6000;
    private static final long SLA_LATENCY_MS = 3000L;
    private static final int DEFAULT_MAX_CASES = 100;
    private static final int HARD_MAX_CASES = 200;
    private static final int MAX_LEVEL1_CRAWL = 20;
    private static final int MAX_LEVEL2_CRAWL = 40;
    private static final int MAX_REDIRECT_HOPS = 4;
    private static final int MAX_SNAPSHOT_CHARS = 24000;

    private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEADING_TAG = Pattern.compile("<h[12][^>]*>(.*?)</h[12]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANCHOR_TAG = Pattern.compile("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FORM_TAG = Pattern.compile("<form\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_ATTR = Pattern.compile("action\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_ATTR = Pattern.compile("method\\s*=\\s*[\"']([a-zA-Z]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBED_BLOCK_TAG = Pattern.compile("<(iframe|object|embed|video|audio|noscript)\\b[^>]*>[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBED_SELF_TAG = Pattern.compile("<(iframe|object|embed|video|audio|source)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TAG = Pattern.compile("<style\\b[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_REFRESH = Pattern.compile("<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_EVENT = Pattern.compile("\\s+on[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_OPEN_TAG = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_STRIP = Pattern.compile("<[^>]+>");
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".css", ".js",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".zip", ".rar", ".mp4", ".mp3", ".woff", ".woff2"
    );

    private static final Map<String, String> USE_CASE_TITLES = Map.ofEntries(
        Map.entry("HOM", "Home & Spotlight"),
        Map.entry("NAV", "Global Navigation & Language"),
        Map.entry("NWS", "News, Notices & Admissions"),
        Map.entry("COO", "Cooperation & External Activities"),
        Map.entry("TRN", "Training & Academic Programs"),
        Map.entry("STU", "Student Life & Key Milestones"),
        Map.entry("RSH", "Research & Science Projects"),
        Map.entry("ABT", "About the Department"),
        Map.entry("API", "API, Forms & Interactive Endpoints"),
        Map.entry("PRF", "Performance, Search & Deep Checks")
    );

    private final TestSuiteRepository suites;
    private final TargetPolicy policy;
    private final TargetHttpClient http;
    private final AiClient ai;
    private final ObjectMapper json;

    public TestSuiteService(TestSuiteRepository suites, TargetPolicy policy, TargetHttpClient http, AiClient ai, ObjectMapper json) {
        this.suites = suites;
        this.policy = policy;
        this.http = http;
        this.ai = ai;
        this.json = json;
    }

    public List<TestSuiteSummary> list(UUID owner) {
        return suites.findByUserIdOrderByUpdatedAtDesc(owner).stream().map(this::summary).toList();
    }

    public TestSuiteView get(UUID owner, UUID suiteId) {
        return view(owned(owner, suiteId));
    }

    @Transactional
    public void delete(UUID owner, UUID suiteId) {
        suites.delete(owned(owner, suiteId));
    }

    /** Deep BFS Crawl 3 tầng (tối đa 100 Test Cases) và sinh YAML + Mini-Web Preview snapshots. */
    @Transactional
    public TestSuiteView createAndGenerate(UUID owner, CreateSuiteRequest req) {
        int maxCases = req.maxCases() == null
            ? DEFAULT_MAX_CASES
            : Math.max(10, Math.min(HARD_MAX_CASES, req.maxCases()));

        URI startUri = policy.validateEndpointForProbe(req.targetUrl(), "GET", Map.of(), null, owner, true);
        FetchResult rootFetch = fetchFollowSameDomain(owner, startUri.toString(), "GET", CRAWL_TIMEOUT_MS, true);
        URI effectiveUri = URI.create(rootFetch.finalUrl());
        String host = effectiveUri.getHost().toLowerCase(Locale.ROOT);
        String origin = buildOrigin(effectiveUri);
        String rootPath = normalizedPath(effectiveUri);

        Map<String, RouteCandidate> discovered = new LinkedHashMap<>();
        Map<String, CrawlSnapshot> snapshotsByPath = new ConcurrentHashMap<>();

        String rootTitle = cleanText(extractTitle(rootFetch.body()));
        if (rootTitle.isBlank()) rootTitle = "Trang chủ " + host;
        List<String> rootTouched = extractTouchedElements(rootFetch.body());
        if (rootFetch.reachable() && isHtml(rootFetch.contentType()) && rootFetch.body() != null) {
            snapshotsByPath.put(rootPath, toCrawlSnapshot(rootFetch, effectiveUri, rootTitle, rootTouched));
        }

        discovered.put("GET:" + normalizeUrlKey(rootFetch.finalUrl()), new RouteCandidate(
            rootFetch.finalUrl(), "GET", "Trang chủ · " + rootTitle, "HOM", "High",
            List.of(rootPath), null, 0, "Direct navigate to " + rootPath
        ));

        // Kiểm tra OpenAPI/Swagger nếu có
        for (String specPath : List.of("/openapi.json", "/v3/api-docs", "/swagger.json")) {
            try {
                FetchResult spec = fetchFollowSameDomain(owner, origin + specPath, "GET", 1500, false);
                if (spec.reachable() && spec.status() >= 200 && spec.status() < 300 && spec.body() != null) {
                    if (collectOpenApiRoutes(spec.body(), origin, rootPath, discovered, maxCases)) break;
                }
            } catch (Exception ignored) {
                // Bỏ qua nếu không có spec
            }
        }

        // Wave 0 -> tìm các nhánh Level 1 từ trang chủ
        List<RouteCandidate> level1Queue = new ArrayList<>();
        if (rootFetch.body() != null && isHtml(rootFetch.contentType())) {
            level1Queue.addAll(collectHtmlRoutes(
                rootFetch.body(), effectiveUri, host, rootPath, List.of(rootPath), 1, discovered, maxCases
            ));
        }

        // Wave 1 (song song) -> crawl sâu các trang Level 1 để lấy HTML snapshot & tìm nhánh Level 2
        List<RouteCandidate> level2Queue = new ArrayList<>();
        if (!level1Queue.isEmpty() && discovered.size() < maxCases) {
            List<RouteCandidate> wave1Targets = pickDiverseCandidates(level1Queue, MAX_LEVEL1_CRAWL);
            List<CrawledPage> wave1Pages = crawlInParallel(owner, wave1Targets, 6);
            for (CrawledPage page : wave1Pages) {
                if (page.snapshot() != null) snapshotsByPath.putIfAbsent(page.path(), page.snapshot());
                if (page.fetch().reachable() && isHtml(page.fetch().contentType()) && page.fetch().body() != null && discovered.size() < maxCases) {
                    List<RouteCandidate> children = collectHtmlRoutes(
                        page.fetch().body(), page.uri(), host, page.path(), page.candidate().visitedPages(),
                        2, discovered, maxCases
                    );
                    level2Queue.addAll(limitPerParent(children, 6));
                }
            }
        }

        // Wave 2 (song song) -> crawl các trang Level 2 để lấy snapshot & tìm nhánh Level 3
        if (!level2Queue.isEmpty() && discovered.size() < maxCases) {
            List<RouteCandidate> wave2Targets = pickBalancedAcrossParents(level2Queue, MAX_LEVEL2_CRAWL);
            List<CrawledPage> wave2Pages = crawlInParallel(owner, wave2Targets, 8);
            for (CrawledPage page : wave2Pages) {
                if (page.snapshot() != null) snapshotsByPath.putIfAbsent(page.path(), page.snapshot());
                if (page.fetch().reachable() && isHtml(page.fetch().contentType()) && page.fetch().body() != null && discovered.size() < maxCases) {
                    collectHtmlRoutes(
                        page.fetch().body(), page.uri(), host, page.path(), page.candidate().visitedPages(),
                        3, discovered, maxCases
                    );
                }
            }
        }

        // Bổ sung các kịch bản kiểm thử sâu (Multi-step Journey, Query/Filter, SLA) khi chọn quy mô lớn (50/100/200)
        if (discovered.size() < maxCases && req.maxCases() != null && req.maxCases() >= 50) {
            expandDeepScenarios(discovered, maxCases);
        }

        List<SuiteUseCaseGroup> groups = synthesizeUseCases(host, effectiveUri.toString(), rootPath, discovered.values(), snapshotsByPath);
        String yamlText = toPlanYaml(groups);

        TestSuite entity = new TestSuite();
        entity.userId = owner;
        entity.name = (req.name() != null && !req.name().isBlank()) ? req.name().trim() : host;
        entity.targetUrl = rootFetch.finalUrl();
        entity.planYaml = yamlText;
        entity.casesJson = encodeGroups(groups);
        recount(entity, groups);
        entity.updatedAt = Instant.now();
        return view(suites.save(entity));
    }

    /** Cập nhật kế hoạch kiểm thử từ Form View hoặc YAML View (đồng bộ 2 chiều). */
    @Transactional
    public TestSuiteView updatePlan(UUID owner, UUID suiteId, UpdateSuitePlanRequest req) {
        TestSuite entity = owned(owner, suiteId);
        List<SuiteUseCaseGroup> existing = decodeGroups(entity.casesJson);
        Map<String, SuiteTestCase> prevById = indexById(existing);

        List<SuiteUseCaseGroup> updated;
        if (req.planYaml() != null && !req.planYaml().isBlank()) {
            updated = fromPlanYaml(req.planYaml(), entity.targetUrl, prevById);
        } else if (req.useCases() != null) {
            updated = normalizeFormGroups(req.useCases(), entity.targetUrl, prevById);
        } else {
            throw ApiException.invalid("Cần cung cấp nội dung planYaml hoặc danh sách useCases");
        }

        for (SuiteUseCaseGroup g : updated) {
            for (SuiteTestCase tc : g.testCases()) {
                policy.validateEndpointForProbe(tc.targetUrl(), tc.httpMethod(), Map.of(), null, owner, true);
            }
        }

        entity.casesJson = encodeGroups(updated);
        entity.planYaml = toPlanYaml(updated);
        recount(entity, updated);
        entity.updatedAt = Instant.now();
        return view(suites.save(entity));
    }

    /** Chạy song song toàn bộ Suite hoặc các Test Case được chọn (8 workers). */
    @Transactional
    public TestSuiteView runSuite(UUID owner, UUID suiteId, SuiteRunRequest req) {
        TestSuite entity = owned(owner, suiteId);
        List<SuiteUseCaseGroup> groups = decodeGroups(entity.casesJson);
        Set<String> selected = (req != null && req.caseIds() != null && !req.caseIds().isEmpty())
            ? new HashSet<>(req.caseIds())
            : null;

        List<SuiteTestCase> targets = new ArrayList<>();
        Set<String> capturedSnapshotPaths = ConcurrentHashMap.newKeySet();
        for (SuiteUseCaseGroup g : groups) {
            for (SuiteTestCase tc : g.testCases()) {
                if (selected == null || selected.contains(tc.id())) {
                    targets.add(tc);
                } else if (tc.preview() != null && tc.preview().htmlSnapshot() != null && !tc.preview().htmlSnapshot().isBlank()) {
                    try {
                        URI u = URI.create(tc.preview().finalUrl() != null && !tc.preview().finalUrl().isBlank() ? tc.preview().finalUrl() : tc.targetUrl());
                        capturedSnapshotPaths.add(baseSnapshotKey(u));
                    } catch (Exception ignored) {
                        // Bỏ qua URL không hợp lệ
                    }
                }
            }
        }
        if (targets.isEmpty()) throw ApiException.invalid("Không có Test Case nào được chọn để chạy");

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, targets.size()));
        Map<String, Future<SuiteTestCase>> futures = new LinkedHashMap<>();
        int timeoutSeconds = Math.max(15, Math.min(65, targets.size()));
        try {
            for (SuiteTestCase tc : targets) {
                futures.put(tc.id(), pool.submit(() -> executeCase(owner, tc, capturedSnapshotPaths)));
            }
            Map<String, SuiteTestCase> completed = new LinkedHashMap<>();
            int aiExplainedFailures = 0;
            for (var entry : futures.entrySet()) {
                SuiteTestCase done = entry.getValue().get(timeoutSeconds, TimeUnit.SECONDS);
                if ("FAILED".equals(done.status()) && ai.configured() && aiExplainedFailures < 3) {
                    done = enrichCaseWithAi(done);
                    aiExplainedFailures++;
                }
                completed.put(entry.getKey(), done);
            }
            List<SuiteUseCaseGroup> updated = groups.stream()
                .map(g -> new SuiteUseCaseGroup(
                    g.useCase(),
                    g.slug(),
                    g.testCases().stream().map(tc -> completed.getOrDefault(tc.id(), tc)).toList()
                ))
                .toList();
            entity.casesJson = encodeGroups(updated);
            entity.planYaml = toPlanYaml(updated);
            entity.lastRunAt = Instant.now();
            entity.updatedAt = entity.lastRunAt;
            recount(entity, updated);
            return view(suites.save(entity));
        } catch (Exception e) {
            throw ApiException.invalid("Lỗi thực thi Test Suite: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Tóm tắt & giải thích chuyên sâu bằng AI cho Preview, Error, Cause, Fix của 1 Test Case. */
    @Transactional
    public TestSuiteView explainCase(UUID owner, UUID suiteId, String caseId) {
        TestSuite entity = owned(owner, suiteId);
        List<SuiteUseCaseGroup> groups = decodeGroups(entity.casesJson);
        boolean found = false;
        List<SuiteUseCaseGroup> updated = new ArrayList<>();
        for (SuiteUseCaseGroup g : groups) {
            List<SuiteTestCase> cases = new ArrayList<>();
            for (SuiteTestCase tc : g.testCases()) {
                if (Objects.equals(tc.id(), caseId)) {
                    found = true;
                    cases.add(enrichCaseWithAi(tc));
                } else {
                    cases.add(tc);
                }
            }
            updated.add(new SuiteUseCaseGroup(g.useCase(), g.slug(), cases));
        }
        if (!found) throw ApiException.missing();
        entity.casesJson = encodeGroups(updated);
        entity.updatedAt = Instant.now();
        return view(suites.save(entity));
    }

    private SuiteTestCase enrichCaseWithAi(SuiteTestCase tc) {
        SuitePreview p = tc.preview();
        int httpStatus = p != null ? p.httpStatus() : ("PASSED".equals(tc.status()) ? 200 : 0);
        long latencyMs = tc.latencyMs() != null ? tc.latencyMs() : (p != null ? p.latencyMs() : 0L);
        SuiteDiagnosis baseDiag = tc.diagnosis() != null
            ? tc.diagnosis()
            : buildDiagnosis(tc, httpStatus, latencyMs, tc.failureSummary() != null ? tc.failureSummary() : "Kiểm thử hoàn tất", p, "PASSED".equals(tc.status()));

        SuiteDiagnosis finalDiag = new SuiteDiagnosis(
            baseDiag.previewSummary(),
            baseDiag.error(),
            baseDiag.cause(),
            baseDiag.fix(),
            baseDiag.fixPrompt(),
            true
        );

        if (ai.configured()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("caseId", tc.id());
                payload.put("caseName", tc.name());
                payload.put("targetUrl", tc.targetUrl());
                payload.put("httpMethod", tc.httpMethod());
                payload.put("status", tc.status() != null ? tc.status() : "PLANNED");
                payload.put("httpStatus", httpStatus);
                payload.put("latencyMs", latencyMs);
                payload.put("pageTitle", p != null && p.pageTitle() != null ? p.pageTitle() : "");
                payload.put("headings", p != null && p.headings() != null ? p.headings() : List.of());
                payload.put("excerpt", p != null && p.excerpt() != null ? p.excerpt() : "");
                payload.put("failureSummary", tc.failureSummary() != null ? tc.failureSummary() : "");
                payload.put("visitedPages", tc.visitedPages() != null ? tc.visitedPages() : List.of());

                JsonNode aiNode = ai.call("/explain-suite-case", payload);
                String previewSummary = aiNode.path("previewSummary").asText(baseDiag.previewSummary());
                String errorExp = aiNode.path("errorExplanation").asText(baseDiag.error());
                String causeAna = aiNode.path("causeAnalysis").asText(baseDiag.cause());
                String fixSug = aiNode.path("fixSuggestions").asText(baseDiag.fix());
                String fixPrm = aiNode.path("fixPrompt").asText(baseDiag.fixPrompt());
                finalDiag = new SuiteDiagnosis(previewSummary, errorExp, causeAna, fixSug, fixPrm, true);
            } catch (Exception ignored) {
                // Giữ bản tóm tắt tiếng Việt thông minh khi AI offline/timeout
            }
        }

        return new SuiteTestCase(
            tc.id(), tc.priority(), tc.name(), tc.description(), tc.targetUrl(), tc.httpMethod(), tc.steps(),
            tc.status(), tc.lastRunAt(), tc.latencyMs(), tc.failureSummary(),
            tc.runLog(), tc.preview(), tc.playwrightCode(), finalDiag,
            tc.visitedPages(), tc.parentPath(), tc.depth(), tc.actionLabel()
        );
    }

    private SuiteTestCase executeCase(UUID owner, SuiteTestCase tc, Set<String> capturedSnapshotPaths) {
        Instant now = Instant.now();
        List<SuiteStepLog> logs = new ArrayList<>();
        long step1Start = System.nanoTime();
        FetchResult res;
        try {
            URI valid = policy.validateEndpointForProbe(tc.targetUrl(), tc.httpMethod(), Map.of(), null, owner, true);
            res = fetchFollowSameDomain(owner, valid.toString(), tc.httpMethod(), RUN_TIMEOUT_MS, true);
        } catch (Exception e) {
            long ms = Math.max(1L, (System.nanoTime() - step1Start) / 1_000_000L);
            logs.add(new SuiteStepLog(1, "Navigate to " + tc.targetUrl(), "FAILED", ms, e.getMessage()));
            SuiteDiagnosis diag = buildDiagnosis(tc, 0, ms, e.getMessage(), null, false);
            return new SuiteTestCase(
                tc.id(), tc.priority(), tc.name(), tc.description(), tc.targetUrl(), tc.httpMethod(), tc.steps(),
                "FAILED", now, ms, "Không thể kết nối tới " + tc.targetUrl() + ": " + e.getMessage(),
                logs, new SuitePreview(tc.targetUrl(), 0, ms, "", 0, "", List.of(), "", "", List.of()),
                generatePlaywrightCode(tc), diag,
                tc.visitedPages(), tc.parentPath(), tc.depth(), tc.actionLabel()
            );
        }

        if (!res.reachable()) {
            logs.add(new SuiteStepLog(1, "Navigate to " + tc.targetUrl(), "FAILED", res.latencyMs(), res.error()));
            SuiteDiagnosis diag = buildDiagnosis(tc, 0, res.latencyMs(), res.error(), null, false);
            return new SuiteTestCase(
                tc.id(), tc.priority(), tc.name(), tc.description(), tc.targetUrl(), tc.httpMethod(), tc.steps(),
                "FAILED", now, res.latencyMs(), "Lỗi kết nối/timeout: " + res.error(),
                logs, new SuitePreview(res.finalUrl(), 0, res.latencyMs(), "", 0, "", List.of(), "", "", List.of()),
                generatePlaywrightCode(tc), diag,
                tc.visitedPages(), tc.parentPath(), tc.depth(), tc.actionLabel()
            );
        }

        logs.add(new SuiteStepLog(1, "Navigate to " + tc.targetUrl(), "PASSED", res.latencyMs(),
            "HTTP " + res.status() + " (" + res.finalUrl() + ")"));

        boolean httpOk = res.status() >= 200 && res.status() < 400;
        logs.add(new SuiteStepLog(2, "assert: HTTP status is 2xx/3xx", httpOk ? "PASSED" : "FAILED", 1L,
            "Nhận được HTTP " + res.status()));

        boolean slaOk = res.latencyMs() <= SLA_LATENCY_MS;
        logs.add(new SuiteStepLog(3, "assert: Response time <= " + SLA_LATENCY_MS + " ms", slaOk ? "PASSED" : "FAILED", 1L,
            "Đo thực tế: " + res.latencyMs() + " ms"));

        String body = res.body() == null ? "" : res.body();
        String title = cleanText(extractTitle(body));
        List<String> headings = extractHeadings(body);
        boolean contentOk = evaluateContentAssertion(res.status(), res.contentType(), body, title);
        logs.add(new SuiteStepLog(4, "assert: Verify page/API content is rendered without error marker",
            contentOk ? "PASSED" : "FAILED", 1L,
            title.isBlank() ? (body.length() + " bytes") : ("Title: " + title)));

        boolean passed = httpOk && slaOk && contentOk;
        String excerpt = extractExcerpt(body, 450);

        URI finalUri = URI.create(res.finalUrl());
        String snapKey = baseSnapshotKey(finalUri);
        String htmlSnapshot = "";
        if (isHtml(res.contentType()) && !body.isBlank() && capturedSnapshotPaths.add(snapKey)) {
            htmlSnapshot = sanitizeSnapshotHtml(body, finalUri);
        } else if (tc.preview() != null && tc.preview().htmlSnapshot() != null && !tc.preview().htmlSnapshot().isBlank() && capturedSnapshotPaths.add(snapKey)) {
            htmlSnapshot = tc.preview().htmlSnapshot();
        }
        List<String> touched = extractTouchedElements(body);
        if (touched.isEmpty() && tc.preview() != null && tc.preview().touchedElements() != null) {
            touched = tc.preview().touchedElements();
        }

        SuitePreview preview = new SuitePreview(
            res.finalUrl(), res.status(), res.latencyMs(), res.contentType(),
            body.length(), title, headings, excerpt, htmlSnapshot, touched
        );

        String failureSummary = null;
        if (!passed) {
            if (!httpOk) failureSummary = "Trang/API trả về mã lỗi HTTP " + res.status() + " thay vì 200 OK.";
            else if (!slaOk) failureSummary = "Thời gian phản hồi " + res.latencyMs() + " ms vượt ngưỡng SLA " + SLA_LATENCY_MS + " ms.";
            else failureSummary = "Nội dung phản hồi rỗng hoặc có dấu hiệu lỗi hiển thị trên trang đích.";
        }
        SuiteDiagnosis diag = buildDiagnosis(tc, res.status(), res.latencyMs(), failureSummary, preview, passed);

        return new SuiteTestCase(
            tc.id(), tc.priority(), tc.name(), tc.description(), tc.targetUrl(), tc.httpMethod(), tc.steps(),
            passed ? "PASSED" : "FAILED", now, res.latencyMs(), failureSummary,
            logs, preview, generatePlaywrightCode(tc), diag,
            tc.visitedPages(), tc.parentPath(), tc.depth(), tc.actionLabel()
        );
    }

    private boolean evaluateContentAssertion(int status, String contentType, String body, String title) {
        if (status >= 400) return false;
        if (isHtml(contentType)) {
            if (body.isBlank() || body.length() < 80) return false;
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            if (lowerTitle.contains("404") || lowerTitle.contains("not found") || lowerTitle.contains("internal server error")) return false;
            String lowerHead = body.substring(0, Math.min(2000, body.length())).toLowerCase(Locale.ROOT);
            return !(lowerHead.contains("http error 500") || lowerHead.contains("whitelabel error page"));
        }
        return status < 400;
    }

    private SuiteDiagnosis buildDiagnosis(SuiteTestCase tc, int status, long latencyMs, String detail, SuitePreview preview, boolean passed) {
        String pathChain = (tc.visitedPages() != null && !tc.visitedPages().isEmpty())
            ? String.join(" → ", tc.visitedPages())
            : tc.targetUrl();
        String titleInfo = (preview != null && preview.pageTitle() != null && !preview.pageTitle().isBlank())
            ? "Tiêu đề trang: \"" + preview.pageTitle() + "\"."
            : "";
        String headingInfo = (preview != null && preview.headings() != null && !preview.headings().isEmpty())
            ? " Các mục chính trên giao diện: " + String.join(", ", preview.headings().subList(0, Math.min(3, preview.headings().size()))) + "."
            : "";

        if (passed) {
            String previewSummary = "Trang/Endpoint phản hồi ổn định (HTTP " + status + " · " + latencyMs + " ms) theo nhánh duyệt `" + pathChain + "`. "
                + titleInfo + headingInfo;
            String okError = "Không phát hiện lỗi (HTTP " + status + " OK · " + latencyMs + " ms <= SLA " + SLA_LATENCY_MS + " ms).";
            String okCause = "Endpoint hoạt động đúng kỳ vọng, phản hồi đầy đủ nội dung và không có dấu hiệu lỗi máy chủ.";
            String okFix = "Có thể chuyển kịch bản này sang kiểm thử tải RAMP_UP hoặc STEP_STRESS nếu muốn đánh giá giới hạn chịu tải đồng thời.";
            String okPrompt = "Kịch bản " + tc.id() + " (" + tc.name() + ") tại " + tc.httpMethod() + " " + tc.targetUrl()
                + " đã PASSED (HTTP " + status + ", " + latencyMs + " ms). Hãy viết thêm kiểm thử chuyên sâu cho luồng này.";
            return new SuiteDiagnosis(previewSummary.trim(), okError, okCause, okFix, okPrompt, false);
        }

        String previewSummary = "Kiểm thử thất bại trên nhánh `" + pathChain + "` ("
            + (status > 0 ? "HTTP " + status : "Lỗi kết nối/Timeout") + " · " + latencyMs + " ms). "
            + (detail != null ? detail + " " : "") + titleInfo;

        String error = status == 0
            ? "Connection/Timeout Error on " + tc.httpMethod() + " " + tc.targetUrl() + " (" + detail + ")"
            : status >= 400
                ? "AssertionError: Expected HTTP 2xx, got HTTP " + status + " on " + tc.targetUrl()
                : latencyMs > SLA_LATENCY_MS
                    ? "LatencySlaError: Response took " + latencyMs + "ms (limit " + SLA_LATENCY_MS + "ms) on " + tc.targetUrl()
                    : "ContentAssertionError: " + detail;

        String cause;
        String fix;
        if (status == 404) {
            cause = "Đường dẫn `" + tc.targetUrl() + "` không tồn tại hoặc bị thay đổi trên máy chủ (HTTP 404 Not Found).";
            fix = "Kiểm tra lại route/controller trên backend hoặc cập nhật liên kết điều hướng trong menu HTML.";
        } else if (status >= 500) {
            cause = "Máy chủ đích gặp ngoại lệ xử lý nội bộ (HTTP " + status + ") khi phục vụ request này.";
            fix = "Kiểm tra application logs tại thời điểm lỗi, xác minh kết nối cơ sở dữ liệu và xử lý null/exception cho endpoint này.";
        } else if (status == 401 || status == 403) {
            cause = "Endpoint yêu cầu xác thực hoặc quyền truy cập nhưng bước kiểm thử chưa đính kèm phiên đăng nhập hợp lệ.";
            fix = "Cấu hình Scenario Chaining lấy `{{token}}` ở bước đăng nhập hoặc gắn header Authorization cho route này.";
        } else if (status == 0) {
            cause = "Không thể thiết lập kết nối TCP/TLS hoặc phản hồi vượt quá thời gian chờ " + RUN_TIMEOUT_MS + " ms.";
            fix = "Kiểm tra trạng thái dịch vụ đích, cấu hình DNS/Firewall và tối ưu tốc độ phản hồi server.";
        } else if (latencyMs > SLA_LATENCY_MS) {
            cause = "Thời gian phản hồi đạt " + latencyMs + " ms, vượt ngưỡng SLA " + SLA_LATENCY_MS + " ms, có nguy cơ gây nghẽn khi nhiều người truy cập đồng thời.";
            fix = "Bổ sung cache, tối ưu truy vấn DB hoặc kích hoạt bài test tải STEP_STRESS trong LoadSense để tìm nguyên nhân chậm.";
        } else {
            cause = "Trang trả về HTTP " + status + " nhưng thiếu nội dung chuẩn hoặc chứa mẫu thông báo lỗi (`" + (preview != null ? preview.pageTitle() : "") + "`).";
            fix = "Kiểm tra template giao diện hoặc dữ liệu trả về của endpoint để đảm bảo render đầy đủ nội dung chính.";
        }

        String fixPrompt = "Hãy sửa lỗi cho test case " + tc.id() + " (" + tc.name() + "):\n"
            + "- Target: " + tc.httpMethod() + " " + tc.targetUrl() + "\n"
            + "- Tóm tắt trang & luồng duyệt: " + previewSummary.trim() + "\n"
            + "- Lỗi ghi nhận: " + error + "\n"
            + "- Nguyên nhân dự kiến: " + cause + "\n"
            + "- Yêu cầu: " + fix;
        return new SuiteDiagnosis(previewSummary.trim(), error, cause, fix, fixPrompt, false);
    }

    private String generatePlaywrightCode(SuiteTestCase tc) {
        return """
            import { test, expect } from '@playwright/test';

            test('%s: %s', async ({ page }) => {
              const started = Date.now();
              const response = await page.goto('%s', { waitUntil: 'domcontentloaded', timeout: %d });
              expect(response).not.toBeNull();
              expect(response!.status()).toBeLessThan(400);
              const elapsedMs = Date.now() - started;
              expect(elapsedMs).toBeLessThanOrEqual(%d);
              const bodyText = await page.locator('body').innerText();
              expect(bodyText.trim().length).toBeGreaterThan(0);
            });
            """.formatted(tc.id(), tc.name().replace("'", "\\'"), tc.targetUrl(), RUN_TIMEOUT_MS, SLA_LATENCY_MS);
    }

    private record FetchResult(boolean reachable, int status, long latencyMs, String finalUrl, String contentType, String body, String error) {}

    private FetchResult fetchFollowSameDomain(UUID owner, String startUrl, String method, int timeoutMs, boolean autoApprove) {
        String currentUrl = startUrl;
        URI originUri = URI.create(startUrl);
        String baseDomain = rootDomain(originUri.getHost());
        long totalLatency = 0;

        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            TargetHttpClient.ProbeResult res;
            try {
                res = http.probeWithHeaders(method, currentUrl, Map.of("User-Agent", "LoadSense-SuiteRunner/1.0"), null, timeoutMs);
            } catch (Exception e) {
                return new FetchResult(false, 0, totalLatency, currentUrl, "", null, e.getMessage());
            }
            totalLatency += res.latencyMs();
            String ct = res.headers().getOrDefault("content-type", "");
            int code = res.status();
            if (Set.of(301, 302, 303, 307, 308).contains(code)) {
                String loc = res.headers().get("location");
                if (loc != null && !loc.isBlank()) {
                    try {
                        URI next = URI.create(currentUrl).resolve(loc.trim());
                        if (next.getHost() != null && rootDomain(next.getHost()).equals(baseDomain)) {
                            policy.validateEndpointForProbe(next.toString(), method, Map.of(), null, owner, autoApprove);
                            currentUrl = next.toString();
                            continue;
                        }
                    } catch (Exception ignored) {
                        return new FetchResult(true, code, totalLatency, currentUrl, ct, res.body(), null);
                    }
                }
            }
            return new FetchResult(true, code, totalLatency, currentUrl, ct, res.body(), null);
        }
        return new FetchResult(false, 0, totalLatency, currentUrl, "", null, "Quá số lần chuyển hướng (redirect loop)");
    }

    private record RouteCandidate(
        String url, String method, String title, String groupSlug, String priority,
        List<String> visitedPages, String parentPath, int depth, String actionLabel) {}

    private record CrawlSnapshot(
        String finalUrl, int httpStatus, long latencyMs, String contentType,
        int contentBytes, String pageTitle, List<String> headings, String excerpt,
        String htmlSnapshot, List<String> touchedElements) {}

    private record CrawledPage(RouteCandidate candidate, URI uri, String path, FetchResult fetch, CrawlSnapshot snapshot) {}

    private List<CrawledPage> crawlInParallel(UUID owner, List<RouteCandidate> targets, int concurrency) {
        if (targets.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, targets.size()));
        List<Future<CrawledPage>> futures = new ArrayList<>();
        for (RouteCandidate rc : targets) {
            futures.add(pool.submit(() -> {
                try {
                    URI valid = policy.validateEndpointForProbe(rc.url(), "GET", Map.of(), null, owner, true);
                    FetchResult res = fetchFollowSameDomain(owner, valid.toString(), "GET", CRAWL_TIMEOUT_MS, true);
                    URI finalUri = URI.create(res.finalUrl());
                    String path = normalizedPath(finalUri);
                    String title = cleanText(extractTitle(res.body()));
                    if (title.isBlank()) title = rc.title();
                    List<String> touched = extractTouchedElements(res.body());
                    CrawlSnapshot snap = res.reachable() ? toCrawlSnapshot(res, finalUri, title, touched) : null;
                    return new CrawledPage(rc, finalUri, path, res, snap);
                } catch (Exception ignored) {
                    return null;
                }
            }));
        }
        List<CrawledPage> pages = new ArrayList<>();
        try {
            for (Future<CrawledPage> f : futures) {
                try {
                    CrawledPage p = f.get(CRAWL_TIMEOUT_MS + 1500L, TimeUnit.MILLISECONDS);
                    if (p != null) pages.add(p);
                } catch (Exception ignored) {
                    // Bỏ qua nhánh chậm/timeout khi crawl sâu
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return pages;
    }

    private CrawlSnapshot toCrawlSnapshot(FetchResult res, URI pageUri, String title, List<String> touched) {
        String body = res.body() == null ? "" : res.body();
        String excerpt = extractExcerpt(body, 350);
        String htmlSnap = isHtml(res.contentType()) ? sanitizeSnapshotHtml(body, pageUri) : "";
        return new CrawlSnapshot(
            res.finalUrl(), res.status(), res.latencyMs(), res.contentType(),
            body.length(), title, extractHeadings(body), excerpt, htmlSnap, touched
        );
    }

    private String extractExcerpt(String body, int maxLen) {
        if (body == null || body.isBlank()) return "";
        String stripped = SCRIPT_TAG.matcher(body).replaceAll(" ");
        stripped = STYLE_TAG.matcher(stripped).replaceAll(" ");
        String excerpt = cleanText(HTML_STRIP.matcher(stripped).replaceAll(" "));
        return excerpt.length() > maxLen ? excerpt.substring(0, maxLen) + "…" : excerpt;
    }

    private boolean collectOpenApiRoutes(String rawBody, String origin, String rootPath,
                                         Map<String, RouteCandidate> out, int maxCases) {
        try {
            JsonNode root = json.readTree(rawBody);
            JsonNode paths = root.path("paths");
            if (!paths.isObject() || paths.isEmpty()) return false;
            var pathIt = paths.fields();
            while (pathIt.hasNext() && out.size() < maxCases) {
                var entry = pathIt.next();
                String path = entry.getKey().startsWith("/") ? entry.getKey() : "/" + entry.getKey();
                String url = origin + path.replaceAll("\\{[^}]+}", "1");
                var methods = entry.getValue();
                if (!methods.isObject()) continue;
                for (String m : List.of("get", "post", "put", "delete")) {
                    if (!methods.has(m) || out.size() >= maxCases) continue;
                    String summary = methods.path(m).path("summary").asText("").trim();
                    String title = summary.isEmpty() ? (m.toUpperCase(Locale.ROOT) + " " + path) : summary;
                    out.putIfAbsent(m.toUpperCase(Locale.ROOT) + ":" + normalizeUrlKey(url),
                        new RouteCandidate(url, m.toUpperCase(Locale.ROOT), title, "API", "High",
                            List.of(rootPath, path), rootPath, 1, m.toUpperCase(Locale.ROOT) + " " + path));
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<RouteCandidate> collectHtmlRoutes(String html, URI baseUri, String baseHost,
                                                   String parentPath, List<String> parentChain,
                                                   int depth, Map<String, RouteCandidate> out, int maxCases) {
        List<RouteCandidate> added = new ArrayList<>();
        String rootDom = rootDomain(baseHost);
        Matcher anchor = ANCHOR_TAG.matcher(html);
        while (anchor.find() && out.size() < maxCases) {
            String href = anchor.group(1).trim();
            String label = cleanText(HTML_STRIP.matcher(anchor.group(2)).replaceAll(" "));
            if (href.isBlank() || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:") || href.startsWith("javascript:")) continue;
            try {
                URI resolved = baseUri.resolve(href);
                if (!Set.of("http", "https").contains(resolved.getScheme()) || resolved.getHost() == null) continue;
                if (!rootDomain(resolved.getHost()).equals(rootDom)) continue;
                String cleanUrl = stripFragment(resolved.toString());
                String lowerPath = resolved.getPath() == null ? "" : resolved.getPath().toLowerCase(Locale.ROOT);
                if (SKIP_EXTENSIONS.stream().anyMatch(lowerPath::endsWith)) continue;
                String key = "GET:" + normalizeUrlKey(cleanUrl);
                if (out.containsKey(key)) continue;

                String childPath = normalizedPath(resolved);
                if (Objects.equals(childPath, parentPath)) continue;

                if (label.isBlank() || label.length() < 2) label = childPath;
                if (label.length() > 80) label = label.substring(0, 80);

                String slug = classifySlug(lowerPath + " " + label.toLowerCase(Locale.ROOT));
                String prio = depth <= 1 || Set.of("HOM", "NAV", "API").contains(slug) ? "High"
                    : depth == 2 ? "Medium" : "Low";
                List<String> chain = appendChain(parentChain, childPath);
                String action = "Click \"" + label + "\" link";
                RouteCandidate rc = new RouteCandidate(cleanUrl, "GET", label, slug, prio, chain, parentPath, depth, action);
                out.put(key, rc);
                added.add(rc);
            } catch (Exception ignored) {
                // Bỏ qua href không hợp lệ
            }
        }

        Matcher form = FORM_TAG.matcher(html);
        while (form.find() && out.size() < maxCases) {
            String attrs = form.group(1);
            Matcher action = ACTION_ATTR.matcher(attrs);
            if (!action.find()) continue;
            Matcher method = METHOD_ATTR.matcher(attrs);
            String m = method.find() ? method.group(1).toUpperCase(Locale.ROOT) : "GET";
            if (!Set.of("GET", "POST").contains(m)) m = "GET";
            try {
                URI resolved = baseUri.resolve(action.group(1).trim());
                if (resolved.getHost() != null && rootDomain(resolved.getHost()).equals(rootDom)) {
                    String cleanUrl = stripFragment(resolved.toString());
                    String childPath = normalizedPath(resolved);
                    String key = m + ":" + normalizeUrlKey(cleanUrl);
                    if (!out.containsKey(key)) {
                        List<String> chain = appendChain(parentChain, childPath);
                        RouteCandidate rc = new RouteCandidate(
                            cleanUrl, m, "Form Submit · " + childPath, "API", "High",
                            chain, parentPath, depth, "Submit " + m + " form -> " + childPath
                        );
                        out.put(key, rc);
                        added.add(rc);
                    }
                }
            } catch (Exception ignored) {
                // Bỏ qua form action lỗi
            }
        }
        return added;
    }

    private List<RouteCandidate> pickDiverseCandidates(List<RouteCandidate> items, int limit) {
        Map<String, List<RouteCandidate>> bySlug = new LinkedHashMap<>();
        for (RouteCandidate r : items) {
            bySlug.computeIfAbsent(r.groupSlug(), k -> new ArrayList<>()).add(r);
        }
        List<RouteCandidate> picked = new ArrayList<>();
        int round = 0;
        while (picked.size() < limit) {
            boolean progressed = false;
            for (List<RouteCandidate> bucket : bySlug.values()) {
                if (round < bucket.size() && picked.size() < limit) {
                    picked.add(bucket.get(round));
                    progressed = true;
                }
            }
            if (!progressed) break;
            round++;
        }
        return picked;
    }

    private List<RouteCandidate> pickBalancedAcrossParents(List<RouteCandidate> items, int limit) {
        Map<String, List<RouteCandidate>> byParent = new LinkedHashMap<>();
        for (RouteCandidate r : items) {
            byParent.computeIfAbsent(r.parentPath() == null ? "/" : r.parentPath(), k -> new ArrayList<>()).add(r);
        }
        List<RouteCandidate> picked = new ArrayList<>();
        int round = 0;
        while (picked.size() < limit) {
            boolean progressed = false;
            for (List<RouteCandidate> bucket : byParent.values()) {
                if (round < bucket.size() && picked.size() < limit) {
                    picked.add(bucket.get(round));
                    progressed = true;
                }
            }
            if (!progressed) break;
            round++;
        }
        return picked;
    }

    private List<RouteCandidate> limitPerParent(List<RouteCandidate> items, int maxPerParent) {
        return items.size() <= maxPerParent ? items : items.subList(0, maxPerParent);
    }

    private List<String> appendChain(List<String> base, String nextPath) {
        List<String> list = new ArrayList<>(base);
        if (list.isEmpty() || !Objects.equals(list.get(list.size() - 1), nextPath)) {
            list.add(nextPath);
        }
        return list;
    }

    private String classifySlug(String text) {
        if (text.contains("tin-tuc") || text.contains("thong-bao") || text.contains("tuyen-sinh") || text.contains("su-kien") || text.contains("news") || text.contains("admission")) return "NWS";
        if (text.contains("hop-tac") || text.contains("doi-tac") || text.contains("cuu-sinh-vien") || text.contains("cooper") || text.contains("international")) return "COO";
        if (text.contains("sinh-vien") || text.contains("olympic") || text.contains("thuc-tap") || text.contains("do-an") || text.contains("datn") || text.contains("hoc-bong")) return "STU";
        if (text.contains("nghien-cuu") || text.contains("de-tai") || text.contains("science") || text.contains("research") || text.contains("bai-bao")) return "RSH";
        if (text.contains("dao-tao") || text.contains("chuong-trinh") || text.contains("program") || text.contains("train") || text.contains("postgraduate")) return "TRN";
        if (text.contains("gioi-thieu") || text.contains("about") || text.contains("lien-he") || text.contains("contact") || text.contains("bo-mon") || text.contains("co-cau") || text.contains("lecturer") || text.contains("giang-vien")) return "ABT";
        if (text.contains("/api/") || text.contains("/v1/") || text.contains("/demo/") || text.contains("search")) return "API";
        if (text.contains("lang=") || text.contains("/en") || text.contains("/vi") || text.contains("menu")) return "NAV";
        return "NAV";
    }

    private void expandDeepScenarios(Map<String, RouteCandidate> discovered, int maxCases) {
        if (discovered.size() < 12 && maxCases < 150) {
            return;
        }
        List<RouteCandidate> baseRoutes = new ArrayList<>(discovered.values());
        List<String[]> variants = List.of(
            new String[] { "lang=vi", "Chuyển ngôn ngữ Tiếng Việt · ", "NAV", "Switch language ?lang=vi" },
            new String[] { "page=1", "Phân trang & Danh mục · ", "PRF", "Check pagination ?page=1" },
            new String[] { "view=detail", "Kiểm tra hiển thị chi tiết · ", "PRF", "Deep content check ?view=detail" },
            new String[] { "sla=strict", "Kiểm định SLA & hiệu năng · ", "PRF", "SLA & latency check ?sla=strict" }
        );
        int targetTotal = Math.min(maxCases, Math.max(18, baseRoutes.size() * 4));
        for (String[] v : variants) {
            for (RouteCandidate base : baseRoutes) {
                if (discovered.size() >= targetTotal) return;
                if (!"GET".equalsIgnoreCase(base.method())) continue;
                String baseUrl = base.url();
                String sep = baseUrl.contains("?") ? "&" : "?";
                String nextUrl = baseUrl + sep + v[0];
                String key = "GET:" + normalizeUrlKey(nextUrl);
                if (discovered.containsKey(key)) continue;
                URI nextUri = URI.create(nextUrl);
                String childPath = normalizedPath(nextUri);
                String parentPath = normalizedPath(URI.create(baseUrl));
                List<String> chain = appendChain(base.visitedPages(), childPath);
                int nextDepth = Math.min(3, base.depth() + 1);
                discovered.put(key, new RouteCandidate(
                    nextUrl, "GET", v[1] + base.title(), v[2],
                    nextDepth <= 2 ? "Medium" : "Low",
                    chain, parentPath, nextDepth, v[3] + " on " + parentPath
                ));
            }
        }
    }

    private List<SuiteUseCaseGroup> synthesizeUseCases(String host, String rootUrl, String rootPath,
                                                       Collection<RouteCandidate> routes,
                                                       Map<String, CrawlSnapshot> snapshotsByPath) {
        Map<String, List<RouteCandidate>> buckets = new LinkedHashMap<>();
        for (RouteCandidate r : routes) {
            buckets.computeIfAbsent(r.groupSlug(), k -> new ArrayList<>()).add(r);
        }
        Set<String> attachedSnapshotPaths = new HashSet<>();
        List<SuiteUseCaseGroup> groups = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            String slug = entry.getKey();
            String groupName = USE_CASE_TITLES.getOrDefault(slug, "Functional Module (" + slug + ")");
            List<SuiteTestCase> cases = new ArrayList<>();
            int idx = 1;
            for (RouteCandidate r : entry.getValue()) {
                String id = String.format("%s-%03d", slug, idx++);
                List<String> steps = buildJourneySteps(r, rootUrl);
                URI targetUri = URI.create(r.url());
                String path = normalizedPath(targetUri);
                CrawlSnapshot snap = snapshotsByPath.get(path);
                if (snap == null && targetUri.getPath() != null && !targetUri.getPath().isBlank()) {
                    snap = snapshotsByPath.get(normalizedPath(URI.create(targetUri.getPath())));
                }
                SuitePreview preview = null;
                if (snap != null) {
                    String html = attachedSnapshotPaths.add(baseSnapshotKey(targetUri)) ? snap.htmlSnapshot() : "";
                    preview = new SuitePreview(
                        snap.finalUrl(), snap.httpStatus(), snap.latencyMs(), snap.contentType(),
                        snap.contentBytes(), snap.pageTitle(), snap.headings(), snap.excerpt(),
                        html, snap.touchedElements()
                    );
                }
                SuiteTestCase baseCase = new SuiteTestCase(
                    id, r.priority(), r.title(),
                    "Verify " + r.method() + " " + r.url() + " on " + host + " responds reliably and passes content assertions.",
                    r.url(), r.method(), steps,
                    "PLANNED", null, null, null, List.of(), preview, null, null,
                    r.visitedPages(), r.parentPath(), r.depth(), r.actionLabel()
                );
                cases.add(new SuiteTestCase(
                    baseCase.id(), baseCase.priority(), baseCase.name(), baseCase.description(),
                    baseCase.targetUrl(), baseCase.httpMethod(), baseCase.steps(),
                    "PLANNED", null, null, null, List.of(), preview,
                    generatePlaywrightCode(baseCase), null,
                    r.visitedPages(), r.parentPath(), r.depth(), r.actionLabel()
                ));
            }
            groups.add(new SuiteUseCaseGroup(groupName, slug, cases));
        }
        if (groups.isEmpty()) {
            String id = "HOM-001";
            List<String> steps = List.of("Navigate to " + rootUrl, "assert: Verify HTTP 200 and response <= " + SLA_LATENCY_MS + " ms");
            groups.add(new SuiteUseCaseGroup("Home & Spotlight", "HOM", List.of(
                new SuiteTestCase(id, "High", "Verify homepage responds", "Verify primary URL loads properly.", rootUrl, "GET", steps,
                    "PLANNED", null, null, null, List.of(), null, null, null,
                    List.of(rootPath), null, 0, "Direct navigate to " + rootPath)
            )));
        }
        return groups;
    }

    private List<String> buildJourneySteps(RouteCandidate r, String rootUrl) {
        List<String> steps = new ArrayList<>();
        if (r.depth() > 0 && r.parentPath() != null) {
            steps.add("Start from " + rootUrl + " (path: " + String.join(" -> ", r.visitedPages()) + ")");
            steps.add(r.actionLabel() != null ? r.actionLabel() : ("Navigate to " + r.url()));
        } else {
            steps.add("Navigate to " + r.url());
        }
        steps.add("Verify " + r.method() + " response status is HTTP 200–399");
        steps.add("assert: Verify response time is within SLA (<= " + SLA_LATENCY_MS + " ms)");
        steps.add("assert: Verify page/endpoint content renders without server error");
        return steps;
    }

    private String baseSnapshotKey(URI uri) {
        if (uri == null || uri.getPath() == null || uri.getPath().isBlank()) return "/";
        String p = uri.getPath();
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private String sanitizeSnapshotHtml(String html, URI pageUri) {
        if (html == null || html.isBlank()) return "";
        String cleaned = SCRIPT_TAG.matcher(html).replaceAll("");
        cleaned = EMBED_BLOCK_TAG.matcher(cleaned).replaceAll("");
        cleaned = EMBED_SELF_TAG.matcher(cleaned).replaceAll("");
        cleaned = META_REFRESH.matcher(cleaned).replaceAll("");
        cleaned = INLINE_EVENT.matcher(cleaned).replaceAll(" ");
        String baseTag = "<base href=\"" + buildOrigin(pageUri) + "/\" target=\"_blank\">";
        Matcher head = HEAD_OPEN_TAG.matcher(cleaned);
        if (head.find()) {
            cleaned = cleaned.substring(0, head.end()) + baseTag + cleaned.substring(head.end());
        } else {
            cleaned = "<head>" + baseTag + "</head>" + cleaned;
        }
        if (cleaned.length() > MAX_SNAPSHOT_CHARS) {
            cleaned = cleaned.substring(0, MAX_SNAPSHOT_CHARS);
        }
        return cleaned;
    }

    private List<String> extractTouchedElements(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<String> items = new ArrayList<>();
        Matcher anchor = ANCHOR_TAG.matcher(html);
        while (anchor.find() && items.size() < 8) {
            String label = cleanText(HTML_STRIP.matcher(anchor.group(2)).replaceAll(" "));
            if (label.length() >= 3 && label.length() <= 60) {
                String item = "“" + label + "” link";
                if (!items.contains(item)) items.add(item);
            }
        }
        return items;
    }

    private String toPlanYaml(List<SuiteUseCaseGroup> groups) {
        List<Map<String, Object>> ucList = new ArrayList<>();
        for (SuiteUseCaseGroup g : groups) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (SuiteTestCase tc : g.testCases()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", tc.id());
                m.put("priority", tc.priority());
                m.put("name", tc.name());
                m.put("method", tc.httpMethod());
                m.put("url", tc.targetUrl());
                m.put("description", tc.description());
                m.put("steps", tc.steps());
                tcList.add(m);
            }
            Map<String, Object> ug = new LinkedHashMap<>();
            ug.put("useCase", g.useCase());
            ug.put("slug", g.slug());
            ug.put("testCases", tcList);
            ucList.add(ug);
        }
        Map<String, Object> doc = Map.of("useCases", ucList);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return "# LoadSense Auto Test Plan (Form <-> YAML)\n" + new Yaml(opts).dump(doc);
    }

    @SuppressWarnings("unchecked")
    private List<SuiteUseCaseGroup> fromPlanYaml(String yamlText, String defaultUrl, Map<String, SuiteTestCase> prevById) {
        Object parsed;
        try {
            parsed = new Yaml().load(yamlText);
        } catch (Exception e) {
            throw ApiException.invalid("Cú pháp YAML không hợp lệ: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("useCases") instanceof List<?> ucRaw)) {
            throw ApiException.invalid("YAML cần chứa khóa gốc `useCases` dạng danh sách");
        }
        List<SuiteUseCaseGroup> result = new ArrayList<>();
        int autoIdx = 1;
        for (Object item : ucRaw) {
            if (!(item instanceof Map<?, ?> gm)) continue;
            String useCaseName = strVal(gm.get("useCase"), "Use Case");
            String slug = strVal(gm.get("slug"), "TST").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (slug.isBlank()) slug = "TST";
            List<SuiteTestCase> tcs = new ArrayList<>();
            Object rawCases = gm.get("testCases");
            if (rawCases instanceof List<?> caseList) {
                for (Object ci : caseList) {
                    if (!(ci instanceof Map<?, ?> cm)) continue;
                    String id = strVal(cm.get("id"), String.format("%s-%03d", slug, autoIdx++));
                    SuiteTestCase prev = prevById.get(id);
                    String url = strVal(cm.get("url"), prev != null ? prev.targetUrl() : defaultUrl);
                    String method = strVal(cm.get("method"), prev != null ? prev.httpMethod() : "GET").toUpperCase(Locale.ROOT);
                    String prio = strVal(cm.get("priority"), "Medium");
                    String name = strVal(cm.get("name"), id);
                    String desc = strVal(cm.get("description"), "");
                    List<String> steps = new ArrayList<>();
                    if (cm.get("steps") instanceof List<?> sl) {
                        for (Object s : sl) if (s != null) steps.add(String.valueOf(s));
                    }
                    if (steps.isEmpty()) steps = List.of("Navigate to " + url, "assert: Verify HTTP 200");
                    tcs.add(preservePreviousRun(prev, defaultUrl, id, prio, name, desc, url, method, steps));
                }
            }
            if (!tcs.isEmpty()) result.add(new SuiteUseCaseGroup(useCaseName, slug, tcs));
        }
        if (result.isEmpty()) throw ApiException.invalid("Kế hoạch kiểm thử phải có ít nhất 1 Test Case");
        return result;
    }

    private List<SuiteUseCaseGroup> normalizeFormGroups(List<SuiteUseCaseGroup> rawGroups, String defaultUrl, Map<String, SuiteTestCase> prevById) {
        List<SuiteUseCaseGroup> result = new ArrayList<>();
        int autoIdx = 1;
        for (SuiteUseCaseGroup g : rawGroups) {
            if (g.testCases() == null || g.testCases().isEmpty()) continue;
            String slug = (g.slug() == null || g.slug().isBlank()) ? "TST" : g.slug().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            List<SuiteTestCase> tcs = new ArrayList<>();
            for (SuiteTestCase tc : g.testCases()) {
                String id = (tc.id() == null || tc.id().isBlank()) ? String.format("%s-%03d", slug, autoIdx++) : tc.id().trim();
                SuiteTestCase prev = prevById.get(id);
                String url = (tc.targetUrl() == null || tc.targetUrl().isBlank()) ? defaultUrl : tc.targetUrl().trim();
                String method = (tc.httpMethod() == null || tc.httpMethod().isBlank()) ? "GET" : tc.httpMethod().toUpperCase(Locale.ROOT);
                String prio = (tc.priority() == null || tc.priority().isBlank()) ? "Medium" : tc.priority();
                List<String> steps = (tc.steps() == null || tc.steps().isEmpty())
                    ? List.of("Navigate to " + url, "assert: Verify HTTP 200")
                    : tc.steps();
                tcs.add(preservePreviousRun(prev, defaultUrl, id, prio, tc.name() == null ? id : tc.name(), tc.description() == null ? "" : tc.description(), url, method, steps));
            }
            result.add(new SuiteUseCaseGroup(g.useCase() == null ? slug : g.useCase(), slug, tcs));
        }
        if (result.isEmpty()) throw ApiException.invalid("Kế hoạch kiểm thử phải có ít nhất 1 Test Case");
        return result;
    }

    private SuiteTestCase preservePreviousRun(SuiteTestCase prev, String defaultUrl, String id, String prio, String name, String desc,
                                                String url, String method, List<String> steps) {
        boolean unchangedUrl = prev != null && Objects.equals(prev.targetUrl(), url) && Objects.equals(prev.httpMethod(), method);
        String rootPath = safePath(defaultUrl);
        String targetPath = safePath(url);
        List<String> visited = (unchangedUrl && prev.visitedPages() != null && !prev.visitedPages().isEmpty())
            ? prev.visitedPages()
            : (Objects.equals(rootPath, targetPath) ? List.of(rootPath) : List.of(rootPath, targetPath));
        String parent = (unchangedUrl && prev != null) ? prev.parentPath() : (Objects.equals(rootPath, targetPath) ? null : rootPath);
        int depth = (unchangedUrl && prev != null) ? prev.depth() : (Objects.equals(rootPath, targetPath) ? 0 : 1);
        String action = (unchangedUrl && prev != null && prev.actionLabel() != null) ? prev.actionLabel() : ("Navigate to " + targetPath);

        return new SuiteTestCase(
            id, prio, name, desc, url, method, steps,
            unchangedUrl ? prev.status() : "PLANNED",
            unchangedUrl ? prev.lastRunAt() : null,
            unchangedUrl ? prev.latencyMs() : null,
            unchangedUrl ? prev.failureSummary() : null,
            unchangedUrl && prev.runLog() != null ? prev.runLog() : List.of(),
            unchangedUrl ? prev.preview() : null,
            prev != null && prev.playwrightCode() != null ? prev.playwrightCode() : null,
            unchangedUrl ? prev.diagnosis() : null,
            visited, parent, depth, action
        );
    }

    private Map<String, SuiteTestCase> indexById(List<SuiteUseCaseGroup> groups) {
        Map<String, SuiteTestCase> map = new HashMap<>();
        for (SuiteUseCaseGroup g : groups) {
            for (SuiteTestCase tc : g.testCases()) map.put(tc.id(), tc);
        }
        return map;
    }

    private void recount(TestSuite entity, List<SuiteUseCaseGroup> groups) {
        int total = 0, passed = 0, failed = 0, running = 0;
        for (SuiteUseCaseGroup g : groups) {
            for (SuiteTestCase tc : g.testCases()) {
                total++;
                if ("PASSED".equals(tc.status())) passed++;
                else if ("FAILED".equals(tc.status())) failed++;
                else if ("RUNNING".equals(tc.status())) running++;
            }
        }
        entity.totalCases = total;
        entity.passedCases = passed;
        entity.failedCases = failed;
        entity.runningCases = running;
    }

    private TestSuite owned(UUID owner, UUID id) {
        return suites.findByIdAndUserId(id, owner).orElseThrow(ApiException::missing);
    }

    private TestSuiteSummary summary(TestSuite s) {
        return new TestSuiteSummary(s.id, s.name, s.targetUrl, s.totalCases, s.passedCases, s.failedCases, s.runningCases, s.lastRunAt, s.updatedAt);
    }

    private TestSuiteView view(TestSuite s) {
        return new TestSuiteView(
            s.id, s.name, s.targetUrl, s.planYaml, decodeGroups(s.casesJson),
            s.totalCases, s.passedCases, s.failedCases, s.runningCases, s.lastRunAt, s.createdAt, s.updatedAt
        );
    }

    private String encodeGroups(List<SuiteUseCaseGroup> groups) {
        try { return json.writeValueAsString(groups); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<SuiteUseCaseGroup> decodeGroups(String raw) {
        try { return json.readValue(raw, new TypeReference<List<SuiteUseCaseGroup>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String strVal(Object obj, String fallback) {
        if (obj == null) return fallback;
        String s = String.valueOf(obj).trim();
        return s.isEmpty() ? fallback : s;
    }

    private String extractTitle(String html) {
        if (html == null) return "";
        Matcher m = TITLE_TAG.matcher(html);
        return m.find() ? HTML_STRIP.matcher(m.group(1)).replaceAll("") : "";
    }

    private List<String> extractHeadings(String html) {
        if (html == null) return List.of();
        List<String> list = new ArrayList<>();
        Matcher m = HEADING_TAG.matcher(html);
        while (m.find() && list.size() < 6) {
            String h = cleanText(HTML_STRIP.matcher(m.group(1)).replaceAll(""));
            if (!h.isBlank() && !list.contains(h)) list.add(h);
        }
        return list;
    }

    private String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private String stripFragment(String url) {
        int idx = url.indexOf('#');
        return idx >= 0 ? url.substring(0, idx) : url;
    }

    private String normalizeUrlKey(String url) {
        String s = stripFragment(url);
        return s.endsWith("/") && s.length() > 10 ? s.substring(0, s.length() - 1) : s;
    }

    private String normalizedPath(URI uri) {
        String p = uri.getPath();
        String q = uri.getRawQuery();
        String base = (p == null || p.isBlank()) ? "/" : p;
        if (base.length() > 1 && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return (q != null && !q.isBlank()) ? base + "?" + q : base;
    }

    private String safePath(String url) {
        try { return normalizedPath(URI.create(url)); }
        catch (Exception e) { return "/"; }
    }

    private String buildOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    private String rootDomain(String host) {
        if (host == null) return "";
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("www.")) return lower.substring(4);
        return lower;
    }

    private boolean isHtml(String ct) {
        return ct != null && ct.toLowerCase(Locale.ROOT).contains("text/html");
    }
}
