package jadx.plugins.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.util.PaginationHelper;
import jadx.plugins.mcp.util.PaginationHelper.PageResult;

/**
 * Fast metadata, raw-bytecode string, decompiled code, and security sink search tools.
 */
public final class SearchTools {

	private static final int DEFAULT_TIME_LIMIT_MS = 5000;
	private static final Map<String, List<SinkRule>> SINK_RULES = buildSinkRules();

	private SearchTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager) {
		registry.register(new SearchSymbolsTool(manager));
		registry.register(new SearchStringsTool(manager));
		registry.register(new SearchCodeTool(manager));
		registry.register(new FindSinkCallSitesTool(manager));
	}

	private static Map<String, List<SinkRule>> buildSinkRules() {
		Map<String, List<SinkRule>> map = new LinkedHashMap<>();
		map.put("process", List.of(
				new SinkRule("java/lang/Runtime", "exec"),
				new SinkRule("java/lang/ProcessBuilder", "start")));
		map.put("native", List.of(
				new SinkRule("java/lang/System", "load"),
				new SinkRule("java/lang/System", "loadLibrary"),
				new SinkRule("java/lang/Runtime", "load"),
				new SinkRule("java/lang/Runtime", "loadLibrary")));
		map.put("webview", List.of(
				new SinkRule("android/webkit/WebView", "loadUrl"),
				new SinkRule("android/webkit/WebView", "evaluateJavascript"),
				new SinkRule("android/webkit/WebView", "addJavascriptInterface")));
		map.put("crypto", List.of(
				new SinkRule("javax/crypto/Cipher", "getInstance"),
				new SinkRule("java/security/MessageDigest", "getInstance")));
		map.put("network", List.of(
				new SinkRule("java/net/URL", "openConnection"),
				new SinkRule("okhttp3/OkHttpClient", "newCall")));
		return map;
	}

	private static final class SinkRule {
		private final String internalClass;
		private final String methodName;

		private SinkRule(String internalClass, String methodName) {
			this.internalClass = internalClass;
			this.methodName = methodName;
		}

		private boolean matches(IMethodRef ref) {
			if (!methodName.equals(ref.getName())) {
				return false;
			}
			String parent = ref.getParentClassType();
			return parent != null && parent.contains(internalClass);
		}
	}

	private static final class SearchSymbolsTool implements McpTool {
		private final McpServerManager manager;

		private SearchSymbolsTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "search_symbols";
		}

		@Override
		public String getDescription() {
			return "Search class, method, and field names directly in metadata without decompiling.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("query", ToolSupport.prop("string", "Case-insensitive substring to match"));
			props.add("kind", ToolSupport.prop("string", "Filter: all, class, method, or field"));
			ToolSupport.addClassFilterProps(props);
			props.add("offset", ToolSupport.prop("integer", "Pagination offset"));
			props.add("limit", ToolSupport.prop("integer", "Page size"));
			return ToolSupport.schema(props, "query");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String query = ToolSupport.getString(arguments, "query", "").trim().toLowerCase(Locale.ROOT);
			String kind = ToolSupport.getString(arguments, "kind", "all").trim().toLowerCase(Locale.ROOT);
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			List<JsonObject> matches = new ArrayList<>();
			for (JavaClass cls : decompiler.getClassesWithInners()) {
				if (ToolSupport.shouldSkipClass(cls.getFullName(), arguments)) {
					continue;
				}
				if (("all".equals(kind) || "class".equals(kind))
						&& cls.getFullName().toLowerCase(Locale.ROOT).contains(query)) {
					matches.add(symbolEntry("class", cls.getFullName(), cls.getFullName(), ""));
				}
				if ("all".equals(kind) || "method".equals(kind)) {
					for (JavaMethod mth : cls.getMethods()) {
						if (mth.getName().toLowerCase(Locale.ROOT).contains(query)) {
							matches.add(symbolEntry("method", mth.getFullName(), cls.getFullName(), mth.getShortId()));
						}
					}
				}
				if ("all".equals(kind) || "field".equals(kind)) {
					for (JavaField field : cls.getFields()) {
						if (field.getName().toLowerCase(Locale.ROOT).contains(query)) {
							matches.add(symbolEntry("field", field.getFullName(), cls.getFullName(), field.getShortId()));
						}
					}
				}
			}
			return buildPagedObject(matches, offset, limit, false);
		}

		private static JsonObject symbolEntry(String kind, String name, String className, String shortId) {
			JsonObject obj = new JsonObject();
			obj.addProperty("kind", kind);
			obj.addProperty("name", name);
			obj.addProperty("className", className);
			if (!shortId.isEmpty()) {
				obj.addProperty("shortId", shortId);
			}
			return obj;
		}
	}

	private static final class SearchStringsTool implements McpTool {
		private final McpServerManager manager;

		private SearchStringsTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "search_strings";
		}

		@Override
		public String getDescription() {
			return "Search constant string literals across raw bytecode without decompilation.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("query", ToolSupport.prop("string", "Substring or regex pattern to match in string constants"));
			props.add("regex", ToolSupport.prop("boolean", "Treat query as a case-insensitive regular expression (default false)"));
			ToolSupport.addClassFilterProps(props);
			props.add("timeLimitMs", ToolSupport.prop("integer", "Time budget in milliseconds"));
			props.add("offset", ToolSupport.prop("integer", "Pagination offset"));
			props.add("limit", ToolSupport.prop("integer", "Page size"));
			return ToolSupport.schema(props, "query");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String rawQuery = ToolSupport.getString(arguments, "query", "");
			boolean useRegex = ToolSupport.getBoolean(arguments, "regex", false);
			Pattern pattern = compileOptionalPattern(rawQuery, useRegex);
			String lowerQuery = rawQuery.toLowerCase(Locale.ROOT);
			int timeLimitMs = ToolSupport.getInt(arguments, "timeLimitMs", DEFAULT_TIME_LIMIT_MS);
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			long deadline = System.currentTimeMillis() + Math.max(100, timeLimitMs);
			boolean timedOut = false;
			List<JsonObject> matches = new ArrayList<>();
			for (JavaClass cls : decompiler.getClassesWithInners()) {
				if (System.currentTimeMillis() > deadline) {
					timedOut = true;
					break;
				}
				if (ToolSupport.shouldSkipClass(cls.getFullName(), arguments)) {
					continue;
				}
				for (JavaMethod mth : cls.getMethods()) {
					if (mth.getMethodNode().isNoCode()) {
						continue;
					}
					ICodeReader reader = mth.getMethodNode().getCodeReader();
					if (reader == null) {
						continue;
					}
					reader.visitInstructions(insn -> {
						String val = ToolSupport.readStringRef(insn);
						if (val != null && matchesString(val, lowerQuery, pattern)) {
							JsonObject item = new JsonObject();
							item.addProperty("value", val);
							item.addProperty("className", cls.getFullName());
							item.addProperty("methodName", mth.getName());
							item.addProperty("shortId", mth.getShortId());
							matches.add(item);
						}
					});
				}
			}
			return buildPagedObject(matches, offset, limit, timedOut);
		}

		private static Pattern compileOptionalPattern(String rawQuery, boolean useRegex) {
			if (!useRegex) {
				return null;
			}
			try {
				return Pattern.compile(rawQuery, Pattern.CASE_INSENSITIVE);
			} catch (PatternSyntaxException e) {
				throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
			}
		}

		private static boolean matchesString(String val, String lowerQuery, Pattern pattern) {
			return pattern != null ? pattern.matcher(val).find() : val.toLowerCase(Locale.ROOT).contains(lowerQuery);
		}
	}

	private static final class SearchCodeTool implements McpTool {
		private final McpServerManager manager;

		private SearchCodeTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "search_code";
		}

		@Override
		public String getDescription() {
			return "Search decompiled Java source code with a time budget and pagination.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("query", ToolSupport.prop("string", "Case-insensitive substring to search in decompiled code"));
			props.add("timeLimitMs", ToolSupport.prop("integer", "Time budget in milliseconds"));
			props.add("offset", ToolSupport.prop("integer", "Pagination offset"));
			props.add("limit", ToolSupport.prop("integer", "Page size"));
			return ToolSupport.schema(props, "query");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String query = ToolSupport.getString(arguments, "query", "").toLowerCase(Locale.ROOT);
			int timeLimitMs = ToolSupport.getInt(arguments, "timeLimitMs", DEFAULT_TIME_LIMIT_MS);
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			long deadline = System.currentTimeMillis() + Math.max(100, timeLimitMs);
			boolean timedOut = false;
			List<JsonObject> matches = new ArrayList<>();
			for (JavaClass cls : decompiler.getClasses()) {
				if (System.currentTimeMillis() > deadline) {
					timedOut = true;
					break;
				}
				String code = cls.getCodeInfo().getCodeStr();
				if (code == null || !code.toLowerCase(Locale.ROOT).contains(query)) {
					continue;
				}
				String[] lines = code.split("\r?\n", -1);
				for (int i = 0; i < lines.length; i++) {
					if (lines[i].toLowerCase(Locale.ROOT).contains(query)) {
						JsonObject item = new JsonObject();
						item.addProperty("className", cls.getFullName());
						item.addProperty("line", i + 1);
						item.addProperty("preview", lines[i].trim());
						matches.add(item);
					}
				}
			}
			return buildPagedObject(matches, offset, limit, timedOut);
		}
	}

	private static final class FindSinkCallSitesTool implements McpTool {
		private final McpServerManager manager;

		private FindSinkCallSitesTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "find_sink_call_sites";
		}

		@Override
		public String getDescription() {
			return "Scan raw bytecode for security-sensitive API call sites (process, native, webview, crypto, network, or all) without decompilation.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("category", ToolSupport.prop("string", "Category: all, process, native, webview, crypto, or network"));
			ToolSupport.addClassFilterProps(props);
			props.add("offset", ToolSupport.prop("integer", "Pagination offset"));
			props.add("limit", ToolSupport.prop("integer", "Page size"));
			return ToolSupport.schema(props);
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String category = ToolSupport.getString(arguments, "category", "all").trim().toLowerCase(Locale.ROOT);
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			Map<String, List<SinkRule>> activeRules = selectRules(category);
			List<JsonObject> matches = new ArrayList<>();
			for (JavaClass cls : decompiler.getClassesWithInners()) {
				if (ToolSupport.shouldSkipClass(cls.getFullName(), arguments)) {
					continue;
				}
				for (JavaMethod mth : cls.getMethods()) {
					if (mth.getMethodNode().isNoCode()) {
						continue;
					}
					ICodeReader reader = mth.getMethodNode().getCodeReader();
					if (reader == null) {
						continue;
					}
					reader.visitInstructions(insn -> {
						IMethodRef ref = ToolSupport.readMethodRef(insn);
						if (ref == null) {
							return;
						}
						for (Map.Entry<String, List<SinkRule>> entry : activeRules.entrySet()) {
							for (SinkRule rule : entry.getValue()) {
								if (rule.matches(ref)) {
									JsonObject item = new JsonObject();
									item.addProperty("category", entry.getKey());
									item.addProperty("callerClass", cls.getFullName());
									item.addProperty("callerMethod", mth.getName());
									item.addProperty("callerShortId", mth.getShortId());
									item.addProperty("sinkClass", ref.getParentClassType());
									item.addProperty("sinkMethod", ref.getName());
									matches.add(item);
								}
							}
						}
					});
				}
			}
			return buildPagedObject(matches, offset, limit, false);
		}

		private static Map<String, List<SinkRule>> selectRules(String category) {
			if ("all".equals(category)) {
				return SINK_RULES;
			}
			List<SinkRule> rules = SINK_RULES.get(category);
			if (rules == null) {
				throw new IllegalArgumentException("Unknown sink category: " + category);
			}
			return Map.of(category, rules);
		}
	}

	private static JsonObject buildPagedObject(List<JsonObject> items, int offset, int limit, boolean timedOut) {
		PageResult<JsonObject> page = PaginationHelper.paginate(items, offset, limit);
		JsonArray arr = new JsonArray();
		for (JsonObject item : page.getItems()) {
			arr.add(item);
		}
		JsonObject out = new JsonObject();
		out.add("items", arr);
		out.addProperty("offset", page.getOffset());
		out.addProperty("limit", page.getLimit());
		out.addProperty("total", page.getTotal());
		out.addProperty("hasMore", page.isHasMore());
		out.addProperty("nextOffset", page.getNextOffset());
		out.addProperty("timedOut", timedOut);
		return out;
	}
}
