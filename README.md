# jadx-mcp

An ultra-lightweight (~92 KB), zero-dependency **Model Context Protocol (MCP)** server plugin for [JADX](https://github.com/skylot/jadx).

Unlike multi-process bridges or 20 MB fat-JAR plugins, `jadx-mcp` runs directly inside JADX using a pure Java 11 `ServerSocket` HTTP/1.1 engine and JADX's own bundled `Gson` and `SLF4J` libraries. It does not require Python, Jetty, Javalin, Jackson, or `jdk.httpserver` (which is stripped from official JADX bundled JREs on Windows and macOS).

---

## Features

- **Single-step setup:** Drop the plugin JAR into JADX and point any MCP client to `http://127.0.0.1:8650/mcp` (or use **Plugins → Copy MCP Server Config**).
- **20 focused MCP tools** with pagination, line slicing, character budgets, and automatic outline fallback on large classes.
- **Raw bytecode accuracy:** `get_callees`, `get_xrefs`, `search_strings`, and `find_sink_call_sites` read raw bytecode instructions (`ICodeReader`) so call edges are not lost to decompiler inlining.
- **Security-first local server:** Binds strictly to `127.0.0.1`, validates `Host` and `Origin` headers against DNS rebinding, supports optional constant-time `Bearer` token authentication, and disables XXE when parsing `AndroidManifest.xml`.
- **Multi-window friendly:** Automatically falls back to the next free port (`8651`, `8652`, ...) when multiple JADX windows are open.

---

## Build & Test

Requires Java 11+ (uses `options.release = 11`). When `../jadx` is present alongside this directory, Gradle automatically uses the composite build; otherwise configure a published `jadx-core` artifact.

```bash
./gradlew test jar
```

The output plugin JAR is generated at:

```text
build/libs/jadx-mcp-0.1.0-SNAPSHOT.jar
```

---

## Plugin Options (Preferences → Plugins → JADX MCP Server)

| Option | Default | Description |
| :--- | :--- | :--- |
| `jadx-mcp.enabled` | `yes` | Enable or disable the embedded MCP server |
| `jadx-mcp.port` | `8650` | Preferred TCP port on `127.0.0.1` |
| `jadx-mcp.auto-port-fallback` | `yes` | Pick the next free port if the preferred port is busy |
| `jadx-mcp.token` | `""` | Optional Bearer token required in `Authorization` header |
| `jadx-mcp.max-output-chars` | `32000` | Maximum characters returned per source/resource response |

All options carry `OptionFlag.NOT_CHANGING_CODE`, so changing them never invalidates JADX's decompilation cache.

---

## Client Configuration

Use **Plugins → Copy MCP Server Config** inside `jadx-gui`, or add the following to your MCP client configuration (Cursor, Claude, Antigravity, Windsurf, VS Code):

```json
{
  "mcpServers": {
    "jadx-mcp": {
      "url": "http://127.0.0.1:8650/mcp"
    }
  }
}
```

If `jadx-mcp.token` is set, include `"headers": { "Authorization": "Bearer <your-token>" }`.

---

## Available MCP Tools (20)

| Category | Tools |
| :--- | :--- |
| **Project & Decompilation** | `get_project_info`, `list_classes`, `get_class_outline`, `get_class_source`, `get_method_source` |
| **Call Graph & Hierarchy** | `get_callees`, `get_xrefs`, `get_type_hierarchy`, `get_overrides` |
| **Fast Search & Sinks** | `search_symbols`, `search_strings`, `search_code`, `find_sink_call_sites` |
| **Manifest & Resources** | `list_resources`, `read_resource`, `get_android_manifest`, `get_attack_surface` |
| **GUI & Refactoring** | `get_current_context`, `navigate_to`, `rename_node` |
