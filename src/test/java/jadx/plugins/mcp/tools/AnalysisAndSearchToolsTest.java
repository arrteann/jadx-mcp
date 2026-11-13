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
import jadx.plugins.mcp.fixtures.SampleChild;
import jadx.plugins.mcp.fixtures.SampleTarget;
import jadx.plugins.mcp.mcp.McpJsonRpcHandler;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisAndSearchToolsTest {

	@TempDir
	static Path tempDir;

	private static JadxDecompiler decompiler;
	private static McpJsonRpcHandler rpc;

	@BeforeAll
	static void setUpClass() throws Exception {
		Path targetCls = copyClass(SampleTarget.class, "SampleTarget.class");
		Path childCls = copyClass(SampleChild.class, "SampleChild.class");

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(targetCls.toFile());
		args.getInputFiles().add(childCls.toFile());
		args.setOutDir(tempDir.resolve("out").toFile());
		args.setPluginOptions(Map.of(McpOptions.OPT_ENABLED, "no"));

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

	private static Path copyClass(Class<?> cls, String fileName) throws Exception {
		Path out = tempDir.resolve(fileName);
		try (InputStream in = cls.getResourceAsStream(fileName)) {
			assertThat(in).isNotNull();
			Files.copy(in, out);
		}
		return out;
	}

	@AfterAll
	static void tearDownClass() {
		if (decompiler != null) {
			decompiler.close();
		}
	}

	@Test
	void getCalleesReadsRawBytecodeCallsWithoutDecompilation() {
		JsonObject args = new JsonObject();
		args.addProperty("className", SampleTarget.class.getName());
		args.addProperty("methodName", "run");

		JsonObject res = callTool("get_callees", args);
		JsonArray callees = res.getAsJsonArray("callees");
		assertThat(callees.toString()).contains("greet");
		assertThat(callees.toString()).contains("println");
	}

	@Test
	void getXrefsFindsCallersForMethodAndField() {
		JsonObject mthArgs = new JsonObject();
		mthArgs.addProperty("targetType", "method");
		mthArgs.addProperty("className", SampleTarget.class.getName());
		mthArgs.addProperty("memberName", "greet");

		JsonObject mthRes = callTool("get_xrefs", mthArgs);
		assertThat(mthRes.getAsJsonArray("xrefs").toString()).contains("SampleTarget.run");
		assertThat(mthRes.getAsJsonArray("xrefs").toString()).contains("SampleChild.run");

		JsonObject fieldArgs = new JsonObject();
		fieldArgs.addProperty("targetType", "field");
		fieldArgs.addProperty("className", SampleTarget.class.getName());
		fieldArgs.addProperty("memberName", "secretKey");

		JsonObject fieldRes = callTool("get_xrefs", fieldArgs);
		assertThat(fieldRes.getAsJsonArray("xrefs").toString()).contains("SampleTarget.greet");
	}

	@Test
	void getTypeHierarchyAndOverridesResolveInheritance() {
		JsonObject hierArgs = new JsonObject();
		hierArgs.addProperty("className", SampleTarget.class.getName());
		JsonObject hierRes = callTool("get_type_hierarchy", hierArgs);
		assertThat(hierRes.getAsJsonArray("interfaces").toString()).contains("Runnable");
		assertThat(hierRes.getAsJsonArray("subclasses").toString()).contains(SampleChild.class.getName());

		JsonObject ovrArgs = new JsonObject();
		ovrArgs.addProperty("className", SampleTarget.class.getName());
		ovrArgs.addProperty("methodName", "run");
		JsonObject ovrRes = callTool("get_overrides", ovrArgs);
		assertThat(ovrRes.getAsJsonArray("overrides").toString()).contains(SampleChild.class.getName());
	}

	@Test
	void searchSymbolsStringsAndCodeWorkWithPagination() {
		JsonObject symArgs = new JsonObject();
		symArgs.addProperty("query", "dangerDemo");
		JsonObject symRes = callTool("search_symbols", symArgs);
		assertThat(symRes.getAsJsonArray("items").toString()).contains("dangerDemo");

		JsonObject strArgs = new JsonObject();
		strArgs.addProperty("query", "test-secret");
		JsonObject strRes = callTool("search_strings", strArgs);
		assertThat(strRes.getAsJsonArray("items").toString()).contains("test-secret");

		JsonObject codeArgs = new JsonObject();
		codeArgs.addProperty("query", "hello:");
		codeArgs.addProperty("timeLimitMs", 5000);
		JsonObject codeRes = callTool("search_code", codeArgs);
		assertThat(codeRes.getAsJsonArray("items").toString()).contains("SampleTarget");
	}

	@Test
	void findSinkCallSitesDetectsProcessExecutionInRawBytecode() {
		JsonObject args = new JsonObject();
		args.addProperty("category", "process");
		JsonObject res = callTool("find_sink_call_sites", args);
		JsonArray items = res.getAsJsonArray("items");
		assertThat(items.toString()).contains("dangerDemo");
		assertThat(items.toString()).contains("exec");
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
