# MCP Tools Reference (24 Tools)

`jadx-mcp` exposes 24 focused tools over Streamable HTTP JSON-RPC (`POST /mcp`).

## Common Patterns

- **Pagination:** List and search tools accept `offset` (default `0`) and `limit` (default `50`, max `500`) and return `total`, `hasMore`, and `nextOffset`.
- **Library Noise Filters:** Search and list tools accept:
  - `excludeLibraries` (`boolean`, default `false`): Skip common third-party and AndroidX library packages.
  - `excludeGenerated` (`boolean`, default `false`): Skip generated `R`, `R$*`, and `BuildConfig` classes.
  - `excludePrefixes` (`string`): Comma-separated package/class prefixes to skip.
- **Character Budgets:** Decompile and resource-reading tools accept `maxChars` (defaulting to `jadx-mcp.max-output-chars`, `32000`) and optional `startLine` / `endLine` (1-based).

---

## 1. Project & Decompilation (7 Tools)

| Tool | Description | Key Parameters |
| :--- | :--- | :--- |
| `get_project_info` | Loaded project summary (class/method counts, input files, native libraries). | — |
| `list_classes` | List classes with optional package prefix, query filter, and pagination. | `packagePrefix`, `query`, `excludeLibraries`, `excludeGenerated`, `excludePrefixes`, `offset`, `limit` |
| `get_package_summary` | Summarize classes, method/field counts, superclasses, and subpackages under a package prefix without decompiling. | `packagePrefix`, `offset`, `limit` |
| `get_class_outline` | Lightweight class structure (superclass, interfaces, fields, method signatures with `shortId`s) without full decompilation. | `className` |
| `get_class_source` | Decompile a class in `normal`, `simple`, `fallback`, or `smali` mode with line slicing and automatic outline fallback on large classes. | `className`, `mode`, `startLine`, `endLine`, `maxChars` |
| `get_method_source` | Decompile a single method cleanly by `className` + `methodName` or exact `shortId`. | `className`, `methodName`, `shortId`, `maxChars` |
| `get_method_smali` | Return Smali bytecode for a single method. | `className`, `methodName`, `shortId`, `maxChars` |

---

## 2. Call Graph & Hierarchy (4 Tools)

| Tool | Description | Key Parameters |
| :--- | :--- | :--- |
| `get_callees` | List all internal and external methods called by a method directly from raw bytecode (`ICodeReader`). | `className`, `methodName`, `shortId` |
| `get_xrefs` | Find incoming references (xrefs) to a class, method, or field. | `targetType` (`class` \| `method` \| `field`), `className`, `memberName`, `shortId`, `offset`, `limit` |
| `get_type_hierarchy` | Return superclass, implemented interfaces, and known subclasses/implementors of a class. | `className` |
| `get_overrides` | Find all override-related implementations of a method across subclasses and interfaces. | `className`, `methodName`, `shortId` |

---

## 3. Fast Search, Sinks & JNI (5 Tools)

| Tool | Description | Key Parameters |
| :--- | :--- | :--- |
| `search_symbols` | Search class, method, and field names directly in metadata without decompiling. | `query`, `kind` (`all` \| `class` \| `method` \| `field`), filter flags, `offset`, `limit` |
| `search_strings` | Search constant string literals across raw bytecode without decompilation. | `query`, `regex`, filter flags, `timeLimitMs`, `offset`, `limit` |
| `search_code` | Search decompiled Java source code with a time budget and pagination. | `query`, filter flags, `timeLimitMs`, `offset`, `limit` |
| `find_sink_call_sites` | Scan raw bytecode for security-sensitive API call sites without decompilation. | `category` (`all` \| `process` \| `native` \| `webview` \| `crypto` \| `network`), filter flags, `offset`, `limit` |
| `find_jni_methods` | List all `native` Java/Kotlin methods with standard JNI symbol mangling (`Java_<pkg>_<cls>_<mth>` and overload suffixes), plus `System.loadLibrary`/`load` call sites. | `excludeLibraries`, `excludeGenerated`, `excludePrefixes`, `offset`, `limit` |

---

## 4. Manifest & Resources (5 Tools)

| Tool | Description | Key Parameters |
| :--- | :--- | :--- |
| `list_resources` | List application resources, assets, and native libraries. | `query`, `type` (e.g. `MANIFEST`, `XML`, `IMG`), `offset`, `limit` |
| `read_resource` | Read text content of a resource file by exact path (e.g. `res/values/strings.xml`). | `name`, `startLine`, `endLine`, `maxChars` |
| `search_resources` | Search text resources (`CONTENT_TEXT`: XML, JSON, HTML, manifest, properties) with optional regex, path filter, and time budget. | `query`, `regex`, `nameFilter`, `timeLimitMs`, `offset`, `limit` |
| `get_android_manifest` | Return decoded `AndroidManifest.xml` content with optional line slicing. | `startLine`, `endLine`, `maxChars` |
| `get_attack_surface` | Parse `AndroidManifest.xml` to extract permissions, `debuggable`/`allowBackup`/`usesCleartextTraffic` flags, exported components, and deep links. | — |

---

## 5. GUI & Refactoring (3 Tools)

| Tool | Description | Key Parameters |
| :--- | :--- | :--- |
| `get_current_context` | Return the currently focused class or member under the caret in `jadx-gui`. | — |
| `navigate_to` | Open and jump to a class, method, or field inside `jadx-gui`. | `targetType` (`class` \| `method` \| `field`), `className`, `memberName`, `shortId` |
| `rename_node` | Rename a class, method, or field in JADX by dispatching a `NodeRenamedByUser` event. | `targetType`, `className`, `memberName`, `shortId`, `newName` |
