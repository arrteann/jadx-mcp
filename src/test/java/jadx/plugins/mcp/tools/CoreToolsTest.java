package jadx.plugins.mcp.tools;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.plugins.PluginRuntime;
import jadx.plugins.mcp.JadxMcpPlugin;
import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.fixtures.SampleTarget;
import jadx.plugins.mcp.mcp.McpJsonRpcHandler;

import static org.assertj.core.api.Assertions.assertThat;

class CoreToolsTest {

	@TempDir
	static Path tempDir;

	private static JadxDecompiler decompiler;
	private static McpJsonRpcHandler rpc;

	@BeforeAll
	static void setUpClass() throws Exception {
		Path sampleClass = tempDir.resolve("SampleTarget.class");
		try (InputStream in = SampleTarget.class.getResourceAsStream("SampleTarget.class")) {
			assertThat(in).isNotNull();
			Files.copy(in, sampleClass);
		}

		JadxArgs args = new JadxArgs();
		args.setInputFile(sampleClass.toFile());
		args.setOutDir(tempDir.resolve("out").toFile());
		args.setPluginOptions(Map.of(
				McpOptions.OPT_ENABLED, "no",
				McpOptions.OPT_MAX_CHARS, "1000"));

		decompiler = new JadxDecompiler(args);
		decompiler.load();

		JadxMcpPlugin plugin = decompiler.getPluginManager().getAllPlugins().stream()
				.map(PluginRuntime::getPluginInstance)
				.filter(JadxMcpPlugin.class::isInstance)
				.map(JadxMcpPlugin.class::cast)
				.findFirst()
				.orElseThrow();
		rpc = new McpJsonRpcHandler(plugin.getServerManager().getRegistry(),
				JadxMcpPlugin.PLUGIN_ID, JadxMcpPlugin.VERSION);
	}

	@AfterAll
	static void tearDownClass() {
		if (decompiler != null) {
			decompiler.close();
		}
	}

	@Test
	void getProjectInfoReturnsCountsAndReadiness() {
		JsonObject res = callTool("get_project_info", new JsonObject());
		assertThat(res.get("ready").getAsBoolean()).isTrue();
		assertThat(res.get("classCount").getAsInt()).isGreaterThanOrEqualTo(1);
		assertThat(res.get("methodCount").getAsInt()).isGreaterThanOrEqualTo(3);
		assertThat(res.has("nativeLibs")).isTrue();
	}

	@Test
	void listClassesSupportsFilteringAndPagination() {
		JsonObject args = new JsonObject();
		args.addProperty("packagePrefix", "jadx.plugins.mcp.fixtures");
		args.addProperty("query", "Sample");
		args.addProperty("offset", 0);
		args.addProperty("limit", 10);

		JsonObject res = callTool("list_classes", args);
		assertThat(res.get("total").getAsInt()).isEqualTo(1);
		JsonArray items = res.getAsJsonArray("items");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).getAsString()).isEqualTo(SampleTarget.class.getName());
	}

	@Test
	void getClassOutlineExposesHierarchyFieldsAndMethodsWithShortIds() {
		JsonObject args = new JsonObject();
		args.addProperty("className", SampleTarget.class.getName());

		JsonObject res = callTool("get_class_outline", args);
		assertThat(res.get("className").getAsString()).isEqualTo(SampleTarget.class.getName());
		assertThat(res.getAsJsonArray("interfaces").toString()).contains("Runnable");
		assertThat(res.getAsJsonArray("fields").toString()).contains("secretKey");

		JsonArray methods = res.getAsJsonArray("methods");
		assertThat(methods.size()).isGreaterThanOrEqualTo(3);
		assertThat(methods.get(0).getAsJsonObject().has("shortId")).isTrue();
	}

	@Test
	void getClassSourceSupportsLineRangeModesAndOutlineFallback() {
		JsonObject fullArgs = new JsonObject();
		fullArgs.addProperty("className", SampleTarget.class.getName());
		JsonObject fullRes = callTool("get_class_source", fullArgs);
		assertThat(fullRes.get("code").getAsString()).contains("class SampleTarget");
		assertThat(fullRes.get("code").getAsString()).contains("test-secret");

		JsonObject rangeArgs = new JsonObject();
		rangeArgs.addProperty("className", SampleTarget.class.getName());
		rangeArgs.addProperty("startLine", 1);
		rangeArgs.addProperty("endLine", 5);
		JsonObject rangeRes = callTool("get_class_source", rangeArgs);
		assertThat(rangeRes.get("endLine").getAsInt()).isLessThanOrEqualTo(5);

		JsonObject smaliArgs = new JsonObject();
		smaliArgs.addProperty("className", SampleTarget.class.getName());
		smaliArgs.addProperty("mode", "smali");
		smaliArgs.addProperty("maxChars", 20_000);
		JsonObject smaliRes = callTool("get_class_source", smaliArgs);
		assertThat(smaliRes.get("code").getAsString()).contains(".class");

		// Tiny budget without line range -> returns outline fallback instead of mid-code cut
		JsonObject tinyArgs = new JsonObject();
		tinyArgs.addProperty("className", SampleTarget.class.getName());
		tinyArgs.addProperty("maxChars", 40);
		JsonObject tinyRes = callTool("get_class_source", tinyArgs);
		assertThat(tinyRes.get("outlineFallback").getAsBoolean()).isTrue();
		assertThat(tinyRes.has("outline")).isTrue();
	}

	@Test
	void getMethodSourceDisambiguatesOverloadsViaShortId() {
		JsonObject ambigArgs = new JsonObject();
		ambigArgs.addProperty("className", SampleTarget.class.getName());
		ambigArgs.addProperty("methodName", "greet");

		JsonObject ambigRes = callTool("get_method_source", ambigArgs);
		assertThat(ambigRes.get("ambiguous").getAsBoolean()).isTrue();
		JsonArray candidates = ambigRes.getAsJsonArray("candidates");
		assertThat(candidates).hasSize(2);

		String targetShortId = candidates.get(0).getAsJsonObject().get("shortId").getAsString();
		JsonObject exactArgs = new JsonObject();
		exactArgs.addProperty("className", SampleTarget.class.getName());
		exactArgs.addProperty("shortId", targetShortId);

		JsonObject exactRes = callTool("get_method_source", exactArgs);
		assertThat(exactRes.get("ambiguous").getAsBoolean()).isFalse();
		assertThat(exactRes.get("shortId").getAsString()).isEqualTo(targetShortId);
		assertThat(exactRes.get("code").getAsString()).contains("hello:");
	}

	private static JsonObject callTool(String name, JsonObject arguments) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", arguments);

		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", 1);
		req.addProperty("method", "tools/call");
		req.add("params", params);

		JsonObject response = JsonParser.parseString(rpc.handle(req.toString())).getAsJsonObject();
		assertThat(response.has("error")).isFalse();
		JsonObject result = response.getAsJsonObject("result");
		assertThat(result.get("isError").getAsBoolean()).isFalse();
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
