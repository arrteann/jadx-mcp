package jadx.plugins.mcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Stateless JSON-RPC 2.0 router for the MCP protocol.
 */
public final class McpJsonRpcHandler {

	public static final String PROTOCOL_VERSION = "2025-03-26";
	private static final String JSONRPC_VERSION = "2.0";

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
	private final McpToolRegistry registry;
	private final McpPromptRegistry promptRegistry;
	private final McpResourceRegistry resourceRegistry;
	private final String serverName;
	private final String serverVersion;

	public McpJsonRpcHandler(McpToolRegistry registry, String serverName, String serverVersion) {
		this(registry, new McpPromptRegistry(), new McpResourceRegistry(), serverName, serverVersion);
	}

	public McpJsonRpcHandler(
			McpToolRegistry registry,
			McpPromptRegistry promptRegistry,
			McpResourceRegistry resourceRegistry,
			String serverName,
			String serverVersion) {
		this.registry = registry;
		this.promptRegistry = promptRegistry == null ? new McpPromptRegistry() : promptRegistry;
		this.resourceRegistry = resourceRegistry == null ? new McpResourceRegistry() : resourceRegistry;
		this.serverName = serverName;
		this.serverVersion = serverVersion;
	}

	/**
	 * Handles a raw JSON-RPC 2.0 request string.
	 *
	 * @return serialized JSON response, or {@code null} for notifications (no id).
	 */
	public String handle(String rawJson) {
		if (rawJson == null || rawJson.trim().isEmpty()) {
			return gson.toJson(buildError(null, McpException.INVALID_REQUEST, "Empty request body"));
		}

		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(rawJson);
		} catch (JsonSyntaxException e) {
			return gson.toJson(buildError(null, McpException.PARSE_ERROR, "Parse error: " + e.getMessage()));
		}

		if (parsed.isJsonArray()) {
			return handleBatch(parsed.getAsJsonArray());
		}

		if (!parsed.isJsonObject()) {
			return gson.toJson(buildError(null, McpException.INVALID_REQUEST, "Request must be a JSON object"));
		}

		JsonObject single = handleSingleObject(parsed.getAsJsonObject());
		return single == null ? null : gson.toJson(single);
	}

	private String handleBatch(JsonArray batch) {
		if (batch.size() == 0) {
			return gson.toJson(buildError(null, McpException.INVALID_REQUEST, "Empty batch request"));
		}
		JsonArray responses = new JsonArray();
		for (JsonElement element : batch) {
			if (!element.isJsonObject()) {
				responses.add(buildError(null, McpException.INVALID_REQUEST, "Batch item must be a JSON object"));
				continue;
			}
			JsonObject res = handleSingleObject(element.getAsJsonObject());
			if (res != null) {
				responses.add(res);
			}
		}
		return responses.size() == 0 ? null : gson.toJson(responses);
	}

	private JsonObject handleSingleObject(JsonObject req) {
		JsonElement id = req.get("id");
		boolean isNotification = id == null || id.isJsonNull();

		JsonElement methodEl = req.get("method");
		if (methodEl == null || !methodEl.isJsonPrimitive() || !methodEl.getAsJsonPrimitive().isString()) {
			return isNotification
					? null
					: buildError(id, McpException.INVALID_REQUEST, "Missing or invalid 'method'");
		}

		String method = methodEl.getAsString();
		JsonObject params = req.has("params") && req.get("params").isJsonObject()
				? req.getAsJsonObject("params")
				: new JsonObject();

		if (isNotification) {
			return null;
		}

		try {
			JsonObject result = dispatch(method, params);
			return buildResult(id, result);
		} catch (McpException e) {
			return buildError(id, e.getCode(), e.getMessage());
		} catch (Exception e) {
			return buildError(id, McpException.INTERNAL_ERROR, "Internal error: " + e.getMessage());
		}
	}

	private JsonObject dispatch(String method, JsonObject params) {
		switch (method) {
			case "initialize":
				return handleInitialize();
			case "ping":
				return new JsonObject();
			case "tools/list":
				return handleToolsList();
			case "tools/call":
				return handleToolsCall(params);
			case "prompts/list":
				return handlePromptsList();
			case "prompts/get":
				return handlePromptsGet(params);
			case "resources/list":
				return handleResourcesList();
			case "resources/read":
				return handleResourcesRead(params);
			default:
				throw new McpException(McpException.METHOD_NOT_FOUND, "Method not found: " + method);
		}
	}

	private JsonObject handleInitialize() {
		JsonObject toolsCap = new JsonObject();
		toolsCap.addProperty("listChanged", false);
		JsonObject promptsCap = new JsonObject();
		promptsCap.addProperty("listChanged", false);
		JsonObject resourcesCap = new JsonObject();
		resourcesCap.addProperty("subscribe", false);
		resourcesCap.addProperty("listChanged", false);

		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", toolsCap);
		capabilities.add("prompts", promptsCap);
		capabilities.add("resources", resourcesCap);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", serverName);
		serverInfo.addProperty("version", serverVersion);

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", PROTOCOL_VERSION);
		result.add("capabilities", capabilities);
		result.add("serverInfo", serverInfo);
		return result;
	}

	private JsonObject handleToolsList() {
		JsonArray arr = new JsonArray();
		for (McpTool tool : registry.listAll()) {
			JsonObject item = new JsonObject();
			item.addProperty("name", tool.getName());
			item.addProperty("description", tool.getDescription());
			item.add("inputSchema", tool.getInputSchema());
			arr.add(item);
		}
		JsonObject result = new JsonObject();
		result.add("tools", arr);
		return result;
	}

	private JsonObject handleToolsCall(JsonObject params) {
		JsonElement nameEl = params.get("name");
		if (nameEl == null || !nameEl.isJsonPrimitive() || !nameEl.getAsJsonPrimitive().isString()) {
			throw new McpException(McpException.INVALID_PARAMS, "Missing tool 'name'");
		}
		String toolName = nameEl.getAsString();
		McpTool tool = registry.get(toolName);
		if (tool == null) {
			throw new McpException(McpException.INVALID_PARAMS, "Unknown tool: " + toolName);
		}

		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();

		try {
			JsonElement output = tool.execute(arguments);
			String text = output == null
					? ""
					: (output.isJsonPrimitive() && output.getAsJsonPrimitive().isString()
							? output.getAsString()
							: gson.toJson(output));
			return buildToolResponse(text, false);
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return buildToolResponse("Error executing tool '" + toolName + "': " + msg, true);
		}
	}

	private JsonObject handlePromptsList() {
		JsonArray arr = new JsonArray();
		for (McpPromptRegistry.McpPrompt prompt : promptRegistry.listAll()) {
			JsonObject item = new JsonObject();
			item.addProperty("name", prompt.getName());
			item.addProperty("description", prompt.getDescription());
			item.add("arguments", prompt.getArguments());
			arr.add(item);
		}
		JsonObject result = new JsonObject();
		result.add("prompts", arr);
		return result;
	}

	private JsonObject handlePromptsGet(JsonObject params) {
		String name = requireStringParam(params, "name", "Missing prompt 'name'");
		McpPromptRegistry.McpPrompt prompt = promptRegistry.get(name);
		if (prompt == null) {
			throw new McpException(McpException.INVALID_PARAMS, "Unknown prompt: " + name);
		}
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();

		JsonObject textContent = new JsonObject();
		textContent.addProperty("type", "text");
		textContent.addProperty("text", prompt.render(arguments));

		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", textContent);

		JsonArray messages = new JsonArray();
		messages.add(message);

		JsonObject result = new JsonObject();
		result.addProperty("description", prompt.getDescription());
		result.add("messages", messages);
		return result;
	}

	private JsonObject handleResourcesList() {
		JsonArray arr = new JsonArray();
		for (McpResourceRegistry.McpResource res : resourceRegistry.listAll()) {
			JsonObject item = new JsonObject();
			item.addProperty("uri", res.getUri());
			item.addProperty("name", res.getName());
			item.addProperty("description", res.getDescription());
			item.addProperty("mimeType", res.getMimeType());
			arr.add(item);
		}
		JsonObject result = new JsonObject();
		result.add("resources", arr);
		return result;
	}

	private JsonObject handleResourcesRead(JsonObject params) {
		String uri = requireStringParam(params, "uri", "Missing resource 'uri'");
		McpResourceRegistry.McpResource res = resourceRegistry.get(uri);
		if (res == null) {
			throw new McpException(McpException.INVALID_PARAMS, "Unknown resource: " + uri);
		}
		JsonObject content = new JsonObject();
		content.addProperty("uri", res.getUri());
		content.addProperty("mimeType", res.getMimeType());
		content.addProperty("text", res.read());

		JsonArray contents = new JsonArray();
		contents.add(content);

		JsonObject result = new JsonObject();
		result.add("contents", contents);
		return result;
	}

	private static String requireStringParam(JsonObject params, String key, String errorMessage) {
		JsonElement el = params.get(key);
		if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
			throw new McpException(McpException.INVALID_PARAMS, errorMessage);
		}
		return el.getAsString();
	}

	private static JsonObject buildToolResponse(String text, boolean isError) {
		JsonObject textPart = new JsonObject();
		textPart.addProperty("type", "text");
		textPart.addProperty("text", text);

		JsonArray content = new JsonArray();
		content.add(textPart);

		JsonObject response = new JsonObject();
		response.add("content", content);
		response.addProperty("isError", isError);
		return response;
	}

	private static JsonObject buildResult(JsonElement id, JsonObject result) {
		JsonObject obj = new JsonObject();
		obj.addProperty("jsonrpc", JSONRPC_VERSION);
		obj.add("id", id);
		obj.add("result", result);
		return obj;
	}

	private static JsonObject buildError(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message == null ? "Unknown error" : message);

		JsonObject obj = new JsonObject();
		obj.addProperty("jsonrpc", JSONRPC_VERSION);
		if (id != null) {
			obj.add("id", id);
		} else {
			obj.add("id", com.google.gson.JsonNull.INSTANCE);
		}
		obj.add("error", err);
		return obj;
	}
}
