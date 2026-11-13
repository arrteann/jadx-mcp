package jadx.plugins.mcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonRpcHandlerTest {

	private McpToolRegistry registry;
	private McpJsonRpcHandler handler;

	@BeforeEach
	void setUp() {
		registry = new McpToolRegistry();
		registry.register(new McpTool() {
			@Override
			public String getName() {
				return "echo";
			}

			@Override
			public String getDescription() {
				return "Echoes input message";
			}

			@Override
			public JsonObject getInputSchema() {
				JsonObject schema = new JsonObject();
				schema.addProperty("type", "object");
				return schema;
			}

			@Override
			public JsonElement execute(JsonObject arguments) {
				JsonObject out = new JsonObject();
				out.addProperty("echoed", arguments.get("msg").getAsString());
				return out;
			}
		});
		registry.register(new McpTool() {
			@Override
			public String getName() {
				return "boom";
			}

			@Override
			public String getDescription() {
				return "Always fails";
			}

			@Override
			public JsonObject getInputSchema() {
				return new JsonObject();
			}

			@Override
			public JsonElement execute(JsonObject arguments) {
				throw new IllegalStateException("simulated failure");
			}
		});
		handler = new McpJsonRpcHandler(registry, "jadx-mcp", "0.1.0");
	}

	@Test
	void handlesInitializeHandshake() {
		String raw = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
		JsonObject res = JsonParser.parseString(handler.handle(raw)).getAsJsonObject();

		assertThat(res.get("id").getAsInt()).isEqualTo(1);
		JsonObject result = res.getAsJsonObject("result");
		assertThat(result.get("protocolVersion").getAsString()).isEqualTo(McpJsonRpcHandler.PROTOCOL_VERSION);
		assertThat(result.getAsJsonObject("serverInfo").get("name").getAsString()).isEqualTo("jadx-mcp");
		assertThat(result.getAsJsonObject("capabilities").has("tools")).isTrue();
	}

	@Test
	void returnsNullForNotifications() {
		String raw = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
		assertThat(handler.handle(raw)).isNull();
	}

	@Test
	void handlesPingAndToolsList() {
		JsonObject ping = JsonParser.parseString(
				handler.handle("{\"jsonrpc\":\"2.0\",\"id\":\"p1\",\"method\":\"ping\"}")).getAsJsonObject();
		assertThat(ping.get("id").getAsString()).isEqualTo("p1");
		assertThat(ping.getAsJsonObject("result").size()).isZero();

		JsonObject list = JsonParser.parseString(
				handler.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).getAsJsonObject();
		assertThat(list.getAsJsonObject("result").getAsJsonArray("tools")).hasSize(2);
		assertThat(list.getAsJsonObject("result").getAsJsonArray("tools").get(0)
				.getAsJsonObject().get("name").getAsString()).isEqualTo("echo");
	}

	@Test
	void executesToolAndWrapsResultAsMcpContent() {
		String raw = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"echo\",\"arguments\":{\"msg\":\"hello\"}}}";
		JsonObject res = JsonParser.parseString(handler.handle(raw)).getAsJsonObject();
		JsonObject result = res.getAsJsonObject("result");

		assertThat(result.get("isError").getAsBoolean()).isFalse();
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertThat(text).contains("\"echoed\":\"hello\"");
	}

	@Test
	void wrapsToolRuntimeFailureWithIsErrorTrue() {
		String raw = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"boom\"}}";
		JsonObject res = JsonParser.parseString(handler.handle(raw)).getAsJsonObject();
		JsonObject result = res.getAsJsonObject("result");

		assertThat(result.get("isError").getAsBoolean()).isTrue();
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertThat(text).contains("simulated failure");
	}

	@Test
	void returnsStandardErrorsForInvalidJsonUnknownMethodAndUnknownTool() {
		JsonObject parseErr = JsonParser.parseString(handler.handle("{bad")).getAsJsonObject();
		assertThat(parseErr.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(McpException.PARSE_ERROR);

		JsonObject methodErr = JsonParser.parseString(
				handler.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}")).getAsJsonObject();
		assertThat(methodErr.getAsJsonObject("error").get("code").getAsInt())
				.isEqualTo(McpException.METHOD_NOT_FOUND);

		JsonObject toolErr = JsonParser.parseString(
				handler.handle("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\"}}"))
				.getAsJsonObject();
		assertThat(toolErr.getAsJsonObject("error").get("code").getAsInt())
				.isEqualTo(McpException.INVALID_PARAMS);
	}

	@Test
	void handlesBatchRequestsAndEmptyBatch() {
		String batch = "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
				+ "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}]";
		var arr = JsonParser.parseString(handler.handle(batch)).getAsJsonArray();
		assertThat(arr).hasSize(1);
		assertThat(arr.get(0).getAsJsonObject().get("id").getAsInt()).isEqualTo(10);

		String emptyBatch = "[]";
		JsonObject err = JsonParser.parseString(handler.handle(emptyBatch)).getAsJsonObject();
		assertThat(err.getAsJsonObject("error").get("code").getAsInt())
				.isEqualTo(McpException.INVALID_REQUEST);
	}
}
