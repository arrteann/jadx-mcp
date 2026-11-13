package jadx.plugins.mcp.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

class ResourceToolsTest {

	private static final String SAMPLE_MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
			+ "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
			+ "    package=\"com.example.demo\">\n"
			+ "    <uses-permission android:name=\"android.permission.INTERNET\"/>\n"
			+ "    <application android:debuggable=\"true\" android:allowBackup=\"false\">\n"
			+ "        <activity android:name=\".MainActivity\">\n"
			+ "            <intent-filter>\n"
			+ "                <action android:name=\"android.intent.action.VIEW\"/>\n"
			+ "                <category android:name=\"android.intent.category.DEFAULT\"/>\n"
			+ "                <data android:scheme=\"myapp\" android:host=\"open\" android:pathPrefix=\"/pay\"/>\n"
			+ "            </intent-filter>\n"
			+ "        </activity>\n"
			+ "        <activity android:name=\".HiddenActivity\" android:exported=\"false\"/>\n"
			+ "        <provider android:name=\".DataProvider\" android:authorities=\"com.example.demo.provider\" android:exported=\"true\"/>\n"
			+ "    </application>\n"
			+ "</manifest>\n";

	@TempDir
	static Path tempDir;

	private static JadxDecompiler decompiler;
	private static McpJsonRpcHandler rpc;

	@BeforeAll
	static void setUpClass() throws Exception {
		Path zipFile = tempDir.resolve("sample.jar");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			zos.putNextEntry(new ZipEntry("jadx/plugins/mcp/fixtures/SampleTarget.class"));
			try (InputStream in = SampleTarget.class.getResourceAsStream("SampleTarget.class")) {
				assertThat(in).isNotNull();
				zos.write(in.readAllBytes());
			}
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
			zos.write(SAMPLE_MANIFEST.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("res/values/strings.xml"));
			zos.write("<resources><string name=\"app_name\">DemoApp</string></resources>".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}

		JadxArgs args = new JadxArgs();
		args.setInputFile(zipFile.toFile());
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

	@AfterAll
	static void tearDownClass() {
		if (decompiler != null) {
			decompiler.close();
		}
	}

	@Test
	void listAndReadResourcesWorkWithFiltering() {
		JsonObject listArgs = new JsonObject();
		listArgs.addProperty("query", "strings.xml");
		JsonObject listRes = callTool("list_resources", listArgs);
		assertThat(listRes.get("total").getAsInt()).isEqualTo(1);

		JsonObject readArgs = new JsonObject();
		readArgs.addProperty("name", "res/values/strings.xml");
		JsonObject readRes = callTool("read_resource", readArgs);
		assertThat(readRes.get("content").getAsString()).contains("DemoApp");
	}

	@Test
	void getAndroidManifestReturnsManifestContent() {
		JsonObject res = callTool("get_android_manifest", new JsonObject());
		assertThat(res.get("content").getAsString()).contains("com.example.demo");
	}

	@Test
	void getAttackSurfaceExtractsExportedComponentsAndDeepLinks() {
		JsonObject res = callTool("get_attack_surface", new JsonObject());
		assertThat(res.get("packageName").getAsString()).isEqualTo("com.example.demo");
		assertThat(res.get("debuggable").getAsBoolean()).isTrue();
		assertThat(res.get("allowBackup").getAsBoolean()).isFalse();
		assertThat(res.getAsJsonArray("permissions").toString()).contains("android.permission.INTERNET");

		JsonArray exported = res.getAsJsonArray("exportedComponents");
		assertThat(exported).hasSize(2);
		assertThat(exported.toString()).contains(".MainActivity");
		assertThat(exported.toString()).contains("myapp://open/pay");
		assertThat(exported.toString()).contains(".DataProvider");
		assertThat(exported.toString()).doesNotContain(".HiddenActivity");
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
