package jadx.plugins.mcp;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.plugins.mcp.fixtures.SampleTarget;
import jadx.plugins.mcp.mcp.McpJsonRpcHandler;
import jadx.plugins.mcp.tools.GuiTools;
import jadx.plugins.mcp.tools.GuiTools.ClientFormat;
import jadx.plugins.mcp.ui.McpStatusSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

class V020FeaturesTest {

	@TempDir
	static Path tempDir;

	private static JadxDecompiler decompiler;
	private static JadxMcpPlugin plugin;
	private static McpJsonRpcHandler rpc;

	@BeforeAll
	static void setUpClass() throws Exception {
		Path clsFile = tempDir.resolve("SampleTarget.class");
		try (InputStream in = SampleTarget.class.getResourceAsStream("SampleTarget.class")) {
			assertThat(in).isNotNull();
			Files.copy(in, clsFile);
		}

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(clsFile.toFile());
		args.setOutDir(tempDir.resolve("out").toFile());
		args.setPluginOptions(Map.of(McpOptions.OPT_ENABLED, "no"));

		decompiler = new JadxDecompiler(args);
		decompiler.load();

		plugin = new JadxMcpPlugin();
		McpServerManager manager = plugin.getServerManager();
		manager.setDecompiler(decompiler);
		rpc = new McpJsonRpcHandler(
				manager.getRegistry(),
				manager.getPromptRegistry(),
				manager.getResourceRegistry(),
				JadxMcpPlugin.PLUGIN_ID,
				JadxMcpPlugin.VERSION);
	}

	@AfterAll
	static void tearDownClass() {
		if (plugin != null) {
			plugin.unload();
		}
		if (decompiler != null) {
			decompiler.close();
		}
	}

	@Test
	void getMethodSmaliReturnsMethodInstructions() {
		JsonObject args = new JsonObject();
		args.addProperty("className", SampleTarget.class.getName());
		args.addProperty("shortId", "greet(Ljava/lang/String;)Ljava/lang/String;");
		JsonObject res = callTool("get_method_smali", args);
		assertThat(res.get("mode").getAsString()).isEqualTo("smali");
		assertThat(res.get("code").getAsString()).contains(".method").contains(".end method");
	}

	@Test
	void searchStringsSupportsRegex() {
		JsonObject args = new JsonObject();
		args.addProperty("query", "test-[a-z]+");
		args.addProperty("regex", true);
		JsonObject res = callTool("search_strings", args);
		assertThat(res.getAsJsonArray("items").toString()).contains("test-secret");
	}

	@Test
	void supportsPromptsAndResourcesOverJsonRpc() {
		JsonObject init = JsonParser.parseString(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
				.getAsJsonObject();
		JsonObject caps = init.getAsJsonObject("result").getAsJsonObject("capabilities");
		assertThat(caps.has("prompts")).isTrue();
		assertThat(caps.has("resources")).isTrue();

		JsonObject promptsList = JsonParser.parseString(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}"))
				.getAsJsonObject();
		assertThat(promptsList.getAsJsonObject("result").getAsJsonArray("prompts").size()).isEqualTo(4);

		JsonObject promptGet = JsonParser.parseString(rpc.handle(
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\",\"params\":{\"name\":\"security_audit\",\"arguments\":{\"packagePrefix\":\"com.example\"}}}"))
				.getAsJsonObject();
		String promptText = promptGet.getAsJsonObject("result")
				.getAsJsonArray("messages").get(0).getAsJsonObject()
				.getAsJsonObject("content").get("text").getAsString();
		assertThat(promptText).contains("com.example").contains("get_attack_surface");

		JsonObject resList = JsonParser.parseString(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}"))
				.getAsJsonObject();
		assertThat(resList.getAsJsonObject("result").getAsJsonArray("resources").size()).isEqualTo(3);

		JsonObject resRead = JsonParser.parseString(rpc.handle(
				"{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":\"jadx://project/info\"}}"))
				.getAsJsonObject();
		String infoJson = resRead.getAsJsonObject("result")
				.getAsJsonArray("contents").get(0).getAsJsonObject()
				.get("text").getAsString();
		assertThat(infoJson).contains("\"ready\": true");
	}

	@Test
	void formatsVsCodeClientConfig() {
		String vsCode = GuiTools.buildClientConfigJson("http://127.0.0.1:8650/mcp", "tok", ClientFormat.VSCODE_SERVERS);
		assertThat(vsCode).contains("\"servers\"").contains("\"type\": \"http\"").contains("Bearer tok");

		McpStatusSnapshot snap = McpStatusSnapshot.capture(
				plugin.getServerManager(), plugin.getOptions(), ClientFormat.VSCODE_SERVERS);
		assertThat(snap.getClientConfigJson()).contains("\"servers\"");
	}

	private static JsonObject callTool(String name, JsonObject args) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", args);

		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", 99);
		req.addProperty("method", "tools/call");
		req.add("params", params);

		JsonObject resp = JsonParser.parseString(rpc.handle(req.toString())).getAsJsonObject();
		JsonObject result = resp.getAsJsonObject("result");
		assertThat(result.get("isError").getAsBoolean()).isFalse();
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
