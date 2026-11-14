# Built-in MCP Prompts & Resources

In addition to tools, `jadx-mcp` implements the MCP `prompts/*` and `resources/*` capabilities so clients can launch structured reverse-engineering workflows or read project context directly.

---

## Built-in Prompts (`prompts/list`, `prompts/get`)

| Prompt | Arguments | Workflow |
| :--- | :--- | :--- |
| `security_audit` | `packagePrefix` (optional) | Runs a structured APK security audit: `get_project_info`, `get_attack_surface`, `find_sink_call_sites(category='all')`, and `list_classes` with library noise filtered out. |
| `analyze_webview_deeplinks` | — | Audits exported Activities, deep links (`get_attack_surface`), and WebView sinks (`find_sink_call_sites(category='webview')`), checking `setJavaScriptEnabled`, `addJavascriptInterface`, and URL validation. |
| `analyze_crypto_secrets` | — | Audits cryptographic API usage (`find_sink_call_sites(category='crypto')`) and searches constant strings with regex for hardcoded keys, tokens, secrets, and private keys. |
| `explain_current_class` | — | Reads `get_current_context`, inspects the class outline/source/Smali, and summarizes its role along with callees (`get_callees`) and callers (`get_xrefs`). |

---

## Built-in Resources (`resources/list`, `resources/read`)

| URI | MIME Type | Description |
| :--- | :--- | :--- |
| `jadx://project/info` | `application/json` | Live project summary (counts, native libraries, input files). |
| `jadx://project/manifest` | `application/xml` | Decoded `AndroidManifest.xml` content. |
| `jadx://gui/current-context` | `application/json` | Currently active class/member open in `jadx-gui`. |
