package jadx.plugins.mcp.ui;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.tools.ProjectTools;

import static org.assertj.core.api.Assertions.assertThat;

class McpStatusSnapshotTest {

	@Test
	void capturesRunningServerMetricsAndConfig() throws Exception {
		McpServerManager manager = new McpServerManager("0.1.1");
		ProjectTools.register(manager.getRegistry(), manager);
		McpOptions options = new McpOptions();
		options.setOptions(java.util.Map.of("jadx-mcp.token", "secret-123"));

		try {
			manager.start(0, false, options.getToken());

			URL url = new URL(manager.getActiveUrl());
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Authorization", "Bearer secret-123");
			conn.setRequestProperty("Content-Type", "application/json");
			byte[] payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
			conn.getOutputStream().write(payload);
			assertThat(conn.getResponseCode()).isEqualTo(200);
			conn.disconnect();

			McpStatusSnapshot snap = McpStatusSnapshot.capture(manager, options);
			assertThat(snap.isRunning()).isTrue();
			assertThat(snap.getStatusText()).isEqualTo("Running on port " + manager.getActivePort());
			assertThat(snap.getEndpointUrl()).isEqualTo(manager.getActiveUrl());
			assertThat(snap.getAuthText()).isEqualTo("Bearer token enabled");
			assertThat(snap.getToolCount()).isEqualTo(3);
			assertThat(snap.getRequestCount()).isEqualTo(1L);
			assertThat(snap.getUptimeText()).endsWith("s");
			assertThat(snap.getClientConfigJson()).contains("Bearer secret-123");
		} finally {
			manager.shutdown();
		}
	}

	@Test
	void capturesStoppedAndErrorStatesAndFormatsUptime() {
		McpServerManager manager = new McpServerManager("0.1.1");
		McpOptions options = new McpOptions();
		options.setOptions(java.util.Map.of("jadx-mcp.port", "8765"));

		McpStatusSnapshot stopped = McpStatusSnapshot.capture(manager, options);
		assertThat(stopped.isRunning()).isFalse();
		assertThat(stopped.getStatusText()).isEqualTo("Stopped");
		assertThat(stopped.getEndpointUrl()).isEqualTo("-");
		assertThat(stopped.getAuthText()).contains("Disabled");
		assertThat(stopped.getUptimeText()).isEqualTo("-");
		assertThat(stopped.getClientConfigJson()).contains("http://127.0.0.1:8765/mcp");

		manager.setLastError("Address already in use");
		McpStatusSnapshot errored = McpStatusSnapshot.capture(manager, options);
		assertThat(errored.getStatusText()).isEqualTo("Error: Address already in use");

		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		assertThat(McpStatusSnapshot.formatUptime(start, start.plusSeconds(45))).isEqualTo("45s");
		assertThat(McpStatusSnapshot.formatUptime(start, start.plusSeconds(125))).isEqualTo("2m 05s");
		assertThat(McpStatusSnapshot.formatUptime(start, start.plusSeconds(3661))).isEqualTo("1h 01m 01s");
	}
}
