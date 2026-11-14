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
import jadx.plugins.mcp.ui.McpStatusSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

class V030FeaturesTest {

	static class NativeFixture {
		static void init() {
			System.loadLibrary("native_core");
		}

		native int check_root();

		native int check_root(String path);
	}

	@TempDir
	static Path tempDir;

	private static JadxDecompiler decompiler;
	private static JadxMcpPlugin plugin;
	private static McpJsonRpcHandler rpc;

	@BeforeAll
	static void setUpClass() throws Exception {
		Path targetCls = copyClass(SampleTarget.class, "SampleTarget.class");
		Path nativeCls = copyClass(NativeFixture.class, "NativeFixture.class");

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(targetCls.toFile());
		args.getInputFiles().add(nativeCls.toFile());
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
				JadxMcpPlugin.VERSION,
				manager::recordToolCall);
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

	private static Path copyClass(Class<?> cls, String name) throws Exception {
		Path out = tempDir.resolve(name);
		try (InputStream in = cls.getResourceAsStream(cls.getSimpleName() + ".class")) {
			assertThat(in).isNotNull();
			Files.copy(in, out);
		}
		return out;
	}

	@Test
	void findJniMethodsDetectsNativeMethodsAndLibraryLoads() {
		JsonObject res = callTool("find_jni_methods", new JsonObject());
		assertThat(res.get("total").getAsInt()).isEqualTo(2);
		String itemsJson = res.getAsJsonArray("items").toString();
		assertThat(itemsJson).contains("check_1root__");
		assertThat(itemsJson).contains("check_1root__Ljava_lang_String_2");
		assertThat(res.getAsJsonArray("libraryLoads").toString()).contains("loadLibrary");
	}

	@Test
	void getPackageSummaryAggregatesCountsAndSubpackages() {
		JsonObject args = new JsonObject();
		args.addProperty("packagePrefix", "jadx.plugins.mcp");
		JsonObject res = callTool("get_package_summary", args);
		assertThat(res.get("classCount").getAsInt()).isGreaterThanOrEqualTo(2);
		assertThat(res.get("totalMethods").getAsInt()).isGreaterThan(0);
		assertThat(res.getAsJsonArray("classes").toString()).contains("SampleTarget");
	}

	@Test
	void recordsBoundedRecentToolActivity() {
		McpServerManager mgr = plugin.getServerManager();
		for (int i = 0; i < 12; i++) {
			mgr.recordToolCall("tool_" + i, i, i % 2 == 0);
		}
		assertThat(mgr.getRecentActivity()).hasSize(10);
		assertThat(mgr.getRecentActivity().get(0)).contains("tool_11 [ERR]");

		McpStatusSnapshot snap = McpStatusSnapshot.capture(mgr, plugin.getOptions());
		assertThat(snap.getRecentActivityText()).contains("tool_11 [ERR]");
	}

	private static JsonObject callTool(String name, JsonObject args) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", args);

		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", 77);
		req.addProperty("method", "tools/call");
		req.add("params", params);

		JsonObject resp = JsonParser.parseString(rpc.handle(req.toString())).getAsJsonObject();
		JsonObject result = resp.getAsJsonObject("result");
		assertThat(result.get("isError").getAsBoolean()).isFalse();
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
