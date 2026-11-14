# Troubleshooting

## Port `8650` Already in Use

If multiple JADX windows are open or another service occupies port `8650`, `jadx-mcp.auto-port-fallback` (enabled by default) automatically binds to the next free port (`8651`, `8652`, ...).

Use **Plugins → Copy MCP Server Config** inside `jadx-gui` to copy the configuration with the active port for that window.

---

## `401 Unauthorized` Responses

- Check **Preferences → Plugins → JADX MCP Server → `jadx-mcp.token`**.
- Ensure your MCP client sends `"Authorization": "Bearer <your-token>"`, or clear `jadx-mcp.token` if you want unauthenticated local access on `127.0.0.1`.

---

## `403 Forbidden` (Host / Origin Rejected)

`jadx-mcp` blocks non-loopback `Host` or `Origin` headers to prevent DNS rebinding attacks. Make sure your client connects via `127.0.0.1` or `localhost`.

---

## `NoSuchMethodError` on Startup or Decompile

`jadx-mcp` requires JADX `1.5.x` nightly/`dev` builds or newer containing the `jadx-core` API additions from JADX PR #3062 (`decompileWithMode`, `getSuperClass`, `getInterfaces`, `getShortId`). Update JADX to a recent nightly/`dev` build if you encounter `NoSuchMethodError`.

---

## Large Classes or Search Timeouts

- `get_class_source` automatically falls back to `get_class_outline` when decompiled output exceeds `maxChars` (default `32000`). Use `get_method_source` or `startLine`/`endLine` to inspect specific parts.
- `search_strings`, `search_code`, and `search_resources` enforce a `timeLimitMs` budget and set `timedOut: true` in the JSON response if reached. Narrow the scope with `excludeLibraries=true`, `excludeGenerated=true`, or `nameFilter`.
