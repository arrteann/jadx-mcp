# jadx-mcp

[![CI & Release](https://github.com/arrteann/jadx-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/arrteann/jadx-mcp/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/arrteann/jadx-mcp)](https://github.com/arrteann/jadx-mcp/releases/latest)
[![License](https://img.shields.io/github/license/arrteann/jadx-mcp)](LICENSE)

An ultra-lightweight (~92 KB), zero-dependency **Model Context Protocol (MCP)** server plugin for [JADX](https://github.com/skylot/jadx).

Unlike multi-process bridges or 20 MB fat-JAR plugins, `jadx-mcp` runs directly inside JADX using a pure Java 11 `ServerSocket` HTTP/1.1 engine and JADX's own bundled `Gson` and `SLF4J` libraries. It does not require Python, Jetty, Javalin, Jackson, or `jdk.httpserver` (which is stripped from official JADX bundled JREs on Windows and macOS).

---

## Features

- **Single-step setup:** Drop the plugin JAR into JADX and point any MCP client to `http://127.0.0.1:8650/mcp` (or use **Plugins → Copy MCP Server Config**).
- **24 focused MCP tools** with pagination, line slicing, character budgets, library noise filters, and automatic outline fallback on large classes.
- **MCP `prompts` and `resources`:** Built-in reverse-engineering prompts (`security_audit`, `analyze_webview_deeplinks`, `analyze_crypto_secrets`, `explain_current_class`) and direct resources (`jadx://project/info`, `jadx://project/manifest`, `jadx://gui/current-context`).
- **Raw bytecode accuracy:** `get_callees`, `get_xrefs`, `search_strings`, and `find_sink_call_sites` read raw bytecode instructions (`ICodeReader`) so call edges are not lost to decompiler inlining.
- **Security-first local server:** Binds strictly to `127.0.0.1`, validates `Host` and `Origin` headers against DNS rebinding, supports optional constant-time `Bearer` token authentication, and disables XXE when parsing `AndroidManifest.xml`.
- **Multi-window friendly:** Automatically falls back to the next free port (`8651`, `8652`, ...) when multiple JADX windows are open.

---

## Requirements

- **JADX:** `1.5.x` nightly/`dev` build or newer (requires the `jadx-core` API additions merged in PR #3062: `decompileWithMode`, `getSuperClass`, `getInterfaces`, and `getShortId`).
- **Java:** Java 11+ (tested on macOS, Linux, and Windows, including official JADX bundled JREs without `jdk.httpserver`).

---

## Documentation

Browse the [GitHub Wiki](https://github.com/arrteann/jadx-mcp/wiki) or read the pages directly in [`docs/`](docs/):

- [Installation & Client Setup](docs/Installation.md) — Plugin Manager, GitHub Releases, source build, and MCP client configs
- [MCP Tools Reference (24 Tools)](docs/Tools-Reference.md) — Parameters, filters, pagination, and character budgets
- [Built-in Prompts & Resources](docs/Prompts-and-Resources.md) — Security audit prompts and `jadx://` URIs
- [Architecture & Security](docs/Architecture-and-Security.md) — Zero-dependency HTTP engine, bytecode accuracy, and security model
- [Troubleshooting](docs/Troubleshooting.md) — Port fallback, authentication, JADX version, and timeouts

---

## Installation

Install directly via JADX Plugin Manager:

```bash
jadx plugins --install "github:arrteann:jadx-mcp"
```

Or download `jadx-mcp-<version>.jar` from [GitHub Releases](https://github.com/arrteann/jadx-mcp/releases). See [docs/Installation.md](docs/Installation.md) for full details.

---

## Build & Test

Requires Java 11+ (uses `options.release = 11`). When `../jadx` is present alongside this directory, Gradle automatically uses the composite build; otherwise configure a published `jadx-core` artifact.

```bash
./gradlew check jar
```

To build and install directly into a sibling `../jadx/build/jadx` distribution for local testing:

```bash
./gradlew installToJadx
```

The output plugin JAR is generated under `build/libs/jadx-mcp-<version>.jar`.

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

## Available MCP Tools (24)

| Category | Tools |
| :--- | :--- |
| **Project & Decompilation** | `get_project_info`, `list_classes`, `get_package_summary`, `get_class_outline`, `get_class_source`, `get_method_source`, `get_method_smali` |
| **Call Graph & Hierarchy** | `get_callees`, `get_xrefs`, `get_type_hierarchy`, `get_overrides` |
| **Fast Search & Sinks** | `search_symbols`, `search_strings`, `search_code`, `find_sink_call_sites`, `find_jni_methods` |
| **Manifest & Resources** | `list_resources`, `read_resource`, `search_resources`, `get_android_manifest`, `get_attack_surface` |
| **GUI & Refactoring** | `get_current_context`, `navigate_to`, `rename_node` |

---

## Troubleshooting

- **Port `8650` already in use:** If another JADX window or service is using `8650`, `jadx-mcp.auto-port-fallback` automatically binds to `8651`, `8652`, etc. Use **Plugins → Copy MCP Server Config** in `jadx-gui` to copy the active port.
- **Unauthorized (`401`) responses:** Verify `jadx-mcp.token` in **Preferences → Plugins → JADX MCP Server** matches the `Authorization: Bearer <token>` header in your client config, or clear `jadx-mcp.token` for local unauthenticated access.
- **`NoSuchMethodError` on startup/decompile:** Ensure you are running a JADX `dev`/nightly build (or release) that includes PR #3062 (`decompileWithMode`, `getSuperClass`, `getInterfaces`, `getShortId`).

---

## License

Licensed under the [Apache License 2.0](LICENSE).
