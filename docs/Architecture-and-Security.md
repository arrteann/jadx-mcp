# Architecture & Security

## Zero-Dependency Engine

Unlike multi-process Python/Node bridges or heavy fat-JAR plugins, `jadx-mcp` runs **in-process** inside JADX using:

- A pure Java 11 `ServerSocket` HTTP/1.1 engine (no Jetty, Netty, Javalin, or `jdk.httpserver`, which is absent in official JADX bundled JREs on Windows and macOS).
- JADX's own bundled `Gson` and `SLF4J` dependencies (`compileOnly` at build time, keeping the plugin JAR ~100 KB).
- Streamable HTTP MCP transport (`POST /mcp`, `GET /mcp` SSE stream / health, `DELETE /mcp` session termination).

---

## Raw Bytecode Accuracy

Decompiler optimizations (such as method inlining or dead-code elimination) can hide call edges in Java source output. To preserve accuracy, `get_callees`, `get_xrefs`, `search_strings`, `find_sink_call_sites`, and `find_jni_methods` inspect JADX's raw bytecode instructions (`ICodeReader`) and class/method metadata directly—often without triggering full class decompilation.

---

## Security Model

1. **Loopback-only binding:** The server binds exclusively to `127.0.0.1` (`InetAddress.getLoopbackAddress()`).
2. **DNS rebinding protection:** Incoming `Host` and `Origin` headers are validated so only `localhost`, `127.0.0.1`, or `[::1]` origins/hosts are accepted.
3. **Optional Bearer token:** When `jadx-mcp.token` is configured, requests must supply `Authorization: Bearer <token>`, verified using constant-time comparison (`MessageDigest.isEqual`).
4. **XXE-safe manifest parsing:** `get_attack_surface` configures `DocumentBuilderFactory` with external DTDs and entity expansion disabled.
5. **Non-invalidating options:** All plugin options use `OptionFlag.NOT_CHANGING_CODE`, so toggling ports or tokens never invalidates JADX's decompilation cache.

---

## Status Dialog & Activity Feed

In `jadx-gui`, **Plugins → JADX MCP Server Status** displays:

- Server state, bound `127.0.0.1:<port>` endpoint, and auth status.
- Counts of registered tools, prompts, and resources.
- A bounded **Recent Tool Activity** feed (last 10 tool calls with timestamp, status, and duration).
