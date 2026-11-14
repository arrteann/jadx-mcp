# Installation & Client Setup

## 1. Install `jadx-mcp` in JADX

### Option A: JADX Plugin Manager (GUI or CLI)

Install directly from GitHub releases using JADX's built-in plugin manager:

```bash
jadx plugins --install "github:arrteann:jadx-mcp"
```

Or in `jadx-gui`:

1. Open **Plugins → Manage plugins** (or **Preferences → Plugins**).
2. Install from GitHub location: `github:arrteann:jadx-mcp`.
3. Restart JADX if prompted.

### Option B: Manual JAR Install

1. Download `jadx-mcp-<version>.jar` from the [GitHub Releases](https://github.com/arrteann/jadx-mcp/releases) page.
2. Copy the JAR into your JADX plugins directory (or install via `jadx plugins --install-file jadx-mcp-<version>.jar`).

### Option C: Build from Source

Requires Java 11+ (`options.release = 11`). When `../jadx` is present alongside this repository, Gradle automatically uses the composite build:

```bash
./gradlew check jar
./gradlew installToJadx
```

---

## 2. Verify the Server is Running

When `jadx-gui` starts (with `jadx-mcp.enabled = yes`), the embedded MCP server binds to `127.0.0.1:8650` (or the next free port `8651`, `8652`, ... if `8650` is busy).

In `jadx-gui`, open:

- **Plugins → JADX MCP Server Status** to inspect the active URL, port, tool count, and recent tool activity.
- **Plugins → Copy MCP Server Config** to copy the ready-to-paste JSON snippet with the active port (and Bearer token if configured).

---

## 3. Connect an MCP Client

### Claude Desktop / Cursor / Antigravity / Windsurf

Add `jadx-mcp` to your MCP client's JSON configuration:

```json
{
  "mcpServers": {
    "jadx-mcp": {
      "url": "http://127.0.0.1:8650/mcp"
    }
  }
}
```

### Claude Code (CLI)

```bash
claude mcp add --transport http jadx-mcp http://127.0.0.1:8650/mcp
```

### With Bearer Token Authentication

If `jadx-mcp.token` is set in **Preferences → Plugins → JADX MCP Server**, include the `Authorization` header:

```json
{
  "mcpServers": {
    "jadx-mcp": {
      "url": "http://127.0.0.1:8650/mcp",
      "headers": {
        "Authorization": "Bearer <your-token>"
      }
    }
  }
}
```
