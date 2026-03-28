Here's the complete request processing flow:

  ---
Request Processing Entry Points

1. Application Bootstrap

LegateApplication.java — main() → SpringApplication.run()
- Spring Boot starts Netty (via WebFlux)
- All beans auto-configured via legate-spring-boot-starter

  ---
2. First Touch: RequestIdWebFilter (highest precedence)

Every request hits this filter first:
- Generates req_<nanoid> as a unique request ID
- Stores it in exchange attributes
- Sets X-Legate-Request-Id response header
- Passes request down the chain

  ---
3. Routing: LegateRouterConfig

Functional router maps URLs to handlers:

┌───────────────────────────┬──────────────────────────────────────┐
│           Route           │               Handler                │
├───────────────────────────┼──────────────────────────────────────┤
│ POST /v1/chat/completions │ ChatCompletionHandler::handleRequest │
├───────────────────────────┼──────────────────────────────────────┤
│ GET /v1/models            │ inline lambda                        │
├───────────────────────────┼──────────────────────────────────────┤
│ GET /health               │ inline lambda                        │
├───────────────────────────┼──────────────────────────────────────┤
│ GET /health/ready         │ inline (checks HealthChecker)        │
├───────────────────────────┼──────────────────────────────────────┤
│ POST /admin/keys          │ AdminHandler::createKey              │
├───────────────────────────┼──────────────────────────────────────┤
│ GET /admin/audit          │ AdminHandler::queryAudit             │
├───────────────────────────┼──────────────────────────────────────┤
│ ...                       │ ...                                  │
└───────────────────────────┴──────────────────────────────────────┘

  ---
4. Core Handler: ChatCompletionHandler::handleRequest

The main entry for AI requests. It dispatches based on "stream": true/false, then runs this pipeline on a Virtual Thread:
```
HTTP Request
│
▼
handleRequest()  ← parses body, dispatches streaming vs non-streaming
│
▼
runPreRequestPipeline()  ← runs synchronously on Virtual Thread
├── 1. authenticateAndRateLimit()   — Bearer token → VirtualKeyStore.resolve() → RateLimiter.tryAcquire()
├── 2. checkSpendLimit()            — SpendTracker.isOverBudget()
├── 3. accessController.checkAccess() — glob pattern allow/deny
└── 4. guardPipeline.execute()      — PII, Keyword, MaxTokens, SystemPrompt guards
│
▼
Cache lookup (non-streaming only)  — ResponseCache.get(CacheKey)
│
▼
routeAndExecuteWithRetry()
├── RouteRuleMatcher (conditional routing rules)
├── FallbackChain resolution
├── CircuitBreaker.isCallPermitted()
├── ProviderAdapter.translateRequest()  — unified → provider format
├── UpstreamClient.sendRequest()        — WebClient HTTP call
├── ProviderAdapter.translateResponse() — provider → unified format
└── CircuitBreaker.recordSuccess/Failure()
│
▼
Post-response
├── RateLimiter.reportUsage()  — deduct actual tokens
├── CostCalculator.calculate()
├── SpendTracker.recordSpend()
└── EventBus.publish(CompletionEvent) → async telemetry (logs, metrics, audit)
│
▼
HTTP Response with X-Legate-Request-Id header
```
  ---
Key Design Notes

- Reactive at edges: Netty I/O stays on Reactor threads (Mono/Flux)
- Imperative in core: The synchronous pipeline (runPreRequestPipeline) runs on Virtual Threads via
  Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())
- Telemetry is async: EventBus.publish() never blocks the request path — subscribers run on a separate thread pool