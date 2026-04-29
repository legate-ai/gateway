# Legate AI Gateway

[![CI](https://github.com/legate-ai/legate/actions/workflows/ci.yml/badge.svg)](https://github.com/legate-ai/legate/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-brightgreen.svg)](https://spring.io/projects/spring-boot)

Legate is an open-source AI gateway that allows route, govern, and observe all LLM traffic through a single control point. 
It sits between your applications and LLM providers (OpenAI, Anthropic, Azure, AWS Bedrock, Google Vertex AI), providing:

- **Unified OpenAI-compatible API** — One interface for all providers
- **Intelligent Routing** — Fallback chains, load balancing, circuit breakers
- **Content Governance** — PII detection, keyword filtering, prompt injection protection
- **Observability** — Request logging, metrics, tracing, cost tracking
- **Security** — Virtual keys, rate limiting, spend controls, model access control
- **High Performance** — Built on WebFlux + Virtual Threads

## Quick Start

### With Docker (Recommended)

```bash
# Set your API keys
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."

# Run Legate
docker run -p 8080:8080 \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  -e ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY \
  legateai/legate:latest
```

### From Source

```bash
# Clone the repository
git clone https://github.com/legate-ai/gateway.git
cd gateway

# Build
./gradlew build

# Run
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."
./gradlew :legate-server:bootRun
```

### Test the Gateway

```bash
# OpenAI request through Legate
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'

# Anthropic request (same endpoint!)
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-4-6-sonnet-20241022",
    "messages": [{"role": "user", "content": "Hello, Claude!"}]
  }'

# Streaming
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Tell me a story"}],
    "stream": true
  }'
```

## Features

### Phase 1

- [x] Unified OpenAI-compatible API
- [x] Provider adapters: OpenAI, Anthropic
- [x] Streaming support (SSE)
- [x] Request/response translation
- [x] Structured JSON logging
- [x] Docker containerization
- [x] Health endpoints

### Phase 2

- [ ] Fallback chains with automatic retry
- [ ] Circuit breakers for provider health
- [ ] Load balancing (round-robin, weighted, least-latency)
- [ ] Virtual key authentication
- [ ] Rate limiting (requests/min, tokens/day)
- [ ] Configuration hot-reload
- [ ] Additional providers: Azure, Bedrock, Vertex AI, Ollama

### Phase 3

- [ ] Content guards (PII detection, keyword filtering)
- [ ] Spend tracking and limits
- [ ] Model access control
- [ ] Audit logging
- [ ] Response caching
- [ ] Prometheus metrics

### Phase 4

- [ ] Redis for distributed caching and rate limiting
- [ ] PostgreSQL for audit logs and virtual keys
- [ ] OpenTelemetry distributed tracing
- [ ] Kubernetes Helm chart
- [ ] SDK

## Architecture

```
┌─────────────┐
│   Client    │
│ Application │
└──────┬──────┘
       │ OpenAI-compatible API
       v
┌─────────────────────────────────┐
│       Legate Gateway            │
│  ┌───────────────────────────┐  │
│  │  Request Pipeline         │  │
│  │  • Auth (Phase 2)         │  │
│  │  • Rate Limit (Phase 2)   │  │
│  │  • Guards (Phase 3)       │  │
│  │  • Cache (Phase 3)        │  │
│  │  • Routing Engine         │  │
│  └───────────────────────────┘  │
└────────┬──────────┬─────────────┘
         │          │
         v          v
    ┌────────┐  ┌──────────┐
    │ OpenAI │  │ Anthropic│
    └────────┘  └──────────┘
```

**Key Design Principles:**

- **Zero Spring dependency in core** — Domain logic is pure Java, fully testable
- **Sealed interfaces** — Type-safe decision types with exhaustive pattern matching
- **SPI pattern** — Pluggable providers, guards, cache, rate limiters
- **Async telemetry** — Events never block the request path
- **Virtual Threads** — Blocking operations run on virtual threads via Reactor scheduler

## Configuration

Phase 1 uses environment variables for simplicity:

```bash
# Required
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...

# Optional
SERVER_PORT=8080
```

## Development

### Prerequisites

- Java 25 (Eclipse Temurin recommended)
- Gradle 9.0.0 (included via wrapper)
- Docker (for containerization)

### Build Commands

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run specific module tests
./gradlew :legate-core:test

# Run server locally
./gradlew :legate-server:bootRun

# Build Docker image
docker build -f docker/Dockerfile -t legate:dev .

# Run with Docker Compose
docker compose -f docker/docker-compose.yml up
```

### Project Structure

```
gateway/
├── legate-core/                 # Core domain logic (zero Spring dependency)
├── legate-provider-openai/      # OpenAI adapter
├── legate-provider-anthropic/   # Anthropic adapter
├── legate-spring-boot-starter/  # Auto-configuration
├── legate-server/               # Standalone gateway application
└── legate-bom/                  # Bill of Materials
```

## Provider Support

| Provider | Status | Streaming | Authentication |
|----------|--------|-----------|----------------|
| OpenAI | ✅ | ✅ | Bearer token |
| Anthropic | ✅ | ✅ | API key header |
| Azure OpenAI | 🚧 Phase 2 | | |
| AWS Bedrock | 🚧 Phase 2 | | |
| Google Vertex AI | 🚧 Phase 2 | | |
| Ollama (local) | 🚧 Phase 2 | | |

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](docs/CONTRIBUTING.md) for guidelines.

### How to Contribute

1. **Add a provider adapter** — Implement `ProviderAdapter` interface
2. **Add a guard** — Implement `RequestGuard` or `ResponseGuard`
3. **Improve docs** — Help us make Legate easier to use
4. **Report bugs** — Open an issue with reproduction steps
5. **Suggest features** — Share your use case and requirements

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.


## Community

- **GitHub:** [github.com/legate-ai/legate](https://github.com/legate-ai/legate)
- **Issues:** [Report bugs and request features](https://github.com/legate-ai/legate/issues)
- **Discussions:** [Join the conversation](https://github.com/legate-ai/legate/discussions)

---

Built with ❤️ by the Legate team
