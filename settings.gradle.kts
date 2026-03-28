rootProject.name = "legate"
include(
    "legate-core",
    "legate-provider-openai",
    "legate-provider-anthropic",
    "legate-provider-azure",
    "legate-provider-ollama",
    "legate-spring-boot-starter",
    "legate-server",
    "legate-store-redis",
    "legate-store-postgres",
    "legate-bom"
)