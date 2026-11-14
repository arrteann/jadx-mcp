package jadx.plugins.mcp;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.plugins.JadxPlugin;
import jadx.core.plugins.PluginRuntime;
import jadx.plugins.mcp.util.PaginationHelper;

import static org.assertj.core.api.Assertions.assertThat;

class JadxMcpPluginLifecycleTest {

	@TempDir
	Path tempDir;

	@Test
	void startsQuicklyWithJadxDecompilerAndStopsCleanlyOnClose() throws Exception {
		Path sampleClass = copySampleClassFile();
		JadxArgs args = new JadxArgs();
		args.setInputFile(sampleClass.toFile());
		args.setOutDir(tempDir.resolve("out").toFile());
		args.setPluginOptions(Map.of(
				McpOptions.OPT_PORT, "0",
				McpOptions.OPT_ENABLED, "yes"));

		JadxMcpPlugin mcpPlugin = null;
		long startMs = System.currentTimeMillis();
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			long elapsedMs = System.currentTimeMillis() - startMs;
			assertThat(elapsedMs).isLessThan(5_000L);

			for (PluginRuntime runtime : decompiler.getPluginManager().getAllPlugins()) {
				JadxPlugin plugin = runtime.getPluginInstance();
				if (plugin instanceof JadxMcpPlugin) {
					mcpPlugin = (JadxMcpPlugin) plugin;
					break;
				}
			}
			assertThat(mcpPlugin).isNotNull();
			assertThat(mcpPlugin.getServerManager().isRunning()).isTrue();
			assertThat(mcpPlugin.getServerManager().getDecompiler()).isSameAs(decompiler);

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(URI.create(mcpPlugin.getServerManager().getActiveUrl()))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
					.build();
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
			assertThat(res.statusCode()).isEqualTo(200);
			JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
			assertThat(body.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString())
					.isEqualTo(JadxMcpPlugin.PLUGIN_ID);

			int portBefore = mcpPlugin.getServerManager().getActivePort();
			mcpPlugin.getOptions().setOptions(Map.of(
					McpOptions.OPT_PORT, "0",
					McpOptions.OPT_MAX_CHARS, "16000"));
			assertThat(mcpPlugin.getOptions().getMaxOutputChars()).isEqualTo(16000);
			assertThat(mcpPlugin.getServerManager().getActivePort()).isEqualTo(portBefore);
		}
		assertThat(mcpPlugin.getServerManager().isRunning()).isFalse();
	}

	@Test
	void doesNotStartServerWhenDisabledViaOptions() throws Exception {
		Path sampleClass = copySampleClassFile();
		JadxArgs args = new JadxArgs();
		args.setInputFile(sampleClass.toFile());
		args.setOutDir(tempDir.resolve("out-disabled").toFile());
		args.setPluginOptions(Map.of(McpOptions.OPT_ENABLED, "no"));

		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			JadxMcpPlugin mcpPlugin = decompiler.getPluginManager().getAllPlugins().stream()
					.map(PluginRuntime::getPluginInstance)
					.filter(JadxMcpPlugin.class::isInstance)
					.map(JadxMcpPlugin.class::cast)
					.findFirst()
					.orElse(null);
			assertThat(mcpPlugin).isNotNull();
			assertThat(mcpPlugin.getServerManager().isRunning()).isFalse();
		}
	}

	private Path copySampleClassFile() throws Exception {
		Path out = tempDir.resolve("PaginationHelper.class");
		try (InputStream in = PaginationHelper.class.getResourceAsStream("PaginationHelper.class")) {
			assertThat(in).isNotNull();
			Files.copy(in, out);
		}
		return out;
	}
}
