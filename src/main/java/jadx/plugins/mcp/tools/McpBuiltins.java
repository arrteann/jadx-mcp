package jadx.plugins.mcp.tools;

import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpPromptRegistry;
import jadx.plugins.mcp.mcp.McpPromptRegistry.McpPrompt;
import jadx.plugins.mcp.mcp.McpResourceRegistry;
import jadx.plugins.mcp.mcp.McpResourceRegistry.McpResource;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;

/**
 * Registers built-in reverse-engineering MCP prompts and resources.
 */
public final class McpBuiltins {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private McpBuiltins() {
	}

	public static void register(McpServerManager manager) {
		registerPrompts(manager.getPromptRegistry());
		registerResources(manager.getResourceRegistry(), manager.getRegistry());
	}

	private static void registerPrompts(McpPromptRegistry prompts) {
		JsonArray auditArgs = new JsonArray();
		auditArgs.add(arg("packagePrefix", "Optional package prefix to focus on", false));
		prompts.register(prompt(
				"security_audit",
				"Run a structured Android APK security audit using attack surface, sink scans, and string checks.",
				auditArgs,
				args -> {
					String pkg = ToolSupport.getString(args, "packagePrefix", "").trim();
					String scope = pkg.isEmpty()
							? "Use excludeLibraries=true and excludeGenerated=true."
							: "Focus on packagePrefix '" + pkg + "' with excludeLibraries=true and excludeGenerated=true.";
					return "Perform a structured security audit of the loaded APK:\n"
							+ "1. Call get_project_info and get_attack_surface to inspect permissions, debuggable/cleartext flags, and unprotected exported components.\n"
							+ "2. Call find_sink_call_sites (category='all', excludeLibraries=true, excludeGenerated=true).\n"
							+ "3. Call list_classes (" + scope + ") and inspect high-risk entry points with get_class_outline / get_method_source.\n"
							+ "4. Summarize concrete findings with class/method locations and severity.";
				}));

		prompts.register(prompt(
				"analyze_webview_deeplinks",
				"Inspect exported Activities, deep links, and WebView JavaScript/file-access configurations.",
				new JsonArray(),
				args -> "Analyze WebView and Deep Link security in the loaded APK:\n"
						+ "1. Call get_attack_surface to list exported components and deepLinks.\n"
						+ "2. Call find_sink_call_sites with category='webview' and excludeLibraries=true.\n"
						+ "3. Inspect each matching Activity/method for intent data handling, setJavaScriptEnabled, addJavascriptInterface, and URL validation."));

		prompts.register(prompt(
				"analyze_crypto_secrets",
				"Find cryptographic API usage and hardcoded keys, tokens, or endpoints using regex string search.",
				new JsonArray(),
				args -> "Audit cryptography and hardcoded secrets in the loaded APK:\n"
						+ "1. Call find_sink_call_sites with category='crypto' and excludeLibraries=true.\n"
						+ "2. Call search_strings with regex=true and excludeLibraries=true for patterns like '(api[_-]?key|secret|token|bearer|https?://|-----BEGIN)'.\n"
						+ "3. Inspect callers with get_method_source or get_method_smali and report weak algorithms (ECB, MD5, static IV/key) or embedded credentials."));

		prompts.register(prompt(
				"explain_current_class",
				"Explain the class or method currently open in jadx-gui.",
				new JsonArray(),
				args -> "Inspect and explain what the user is currently viewing in jadx-gui:\n"
						+ "1. Call get_current_context.\n"
						+ "2. Call get_class_outline and get_class_source (or get_method_source / get_method_smali if obfuscated).\n"
						+ "3. Summarize its purpose, key callees (get_callees), and callers (get_xrefs)."));
	}

	private static void registerResources(McpResourceRegistry resources, McpToolRegistry tools) {
		resources.register(new McpResource(
				"jadx://project/info",
				"Project Info",
				"Loaded project summary (counts, native libraries, input files).",
				"application/json",
				() -> executeToolAsText(tools, "get_project_info")));

		resources.register(new McpResource(
				"jadx://project/manifest",
				"AndroidManifest.xml",
				"Decoded AndroidManifest.xml content.",
				"application/xml",
				() -> {
					McpTool tool = tools.get("get_android_manifest");
					if (tool == null) {
						return "";
					}
					JsonObject obj = tool.execute(new JsonObject()).getAsJsonObject();
					return obj.has("content") ? obj.get("content").getAsString() : GSON.toJson(obj);
				}));

		resources.register(new McpResource(
				"jadx://gui/current-context",
				"Current GUI Context",
				"Currently selected class or member in jadx-gui.",
				"application/json",
				() -> executeToolAsText(tools, "get_current_context")));
	}

	private static String executeToolAsText(McpToolRegistry tools, String toolName) {
		McpTool tool = tools.get(toolName);
		if (tool == null) {
			return "{}";
		}
		JsonElement res = tool.execute(new JsonObject());
		return GSON.toJson(res);
	}

	private static JsonObject arg(String name, String description, boolean required) {
		JsonObject obj = new JsonObject();
		obj.addProperty("name", name);
		obj.addProperty("description", description);
		obj.addProperty("required", required);
		return obj;
	}

	private static McpPrompt prompt(
			String name,
			String description,
			JsonArray arguments,
			Function<JsonObject, String> renderer) {
		return new McpPrompt() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public String getDescription() {
				return description;
			}

			@Override
			public JsonArray getArguments() {
				return arguments;
			}

			@Override
			public String render(JsonObject args) {
				return renderer.apply(args == null ? new JsonObject() : args);
			}
		};
	}
}
