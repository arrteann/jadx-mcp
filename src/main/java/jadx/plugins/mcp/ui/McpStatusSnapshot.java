package jadx.plugins.mcp.ui;

import java.time.Duration;
import java.time.Instant;

import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.tools.GuiTools;
import jadx.plugins.mcp.tools.GuiTools.ClientFormat;

/**
 * Immutable snapshot of the embedded MCP server status and client configuration.
 */
public final class McpStatusSnapshot {

	private final boolean running;
	private final String statusText;
	private final String endpointUrl;
	private final String authText;
	private final long requestCount;
	private final int toolCount;
	private final String uptimeText;
	private final String lastError;
	private final String clientConfigJson;

	private McpStatusSnapshot(
			boolean running,
			String statusText,
			String endpointUrl,
			String authText,
			long requestCount,
			int toolCount,
			String uptimeText,
			String lastError,
			String clientConfigJson) {
		this.running = running;
		this.statusText = statusText;
		this.endpointUrl = endpointUrl;
		this.authText = authText;
		this.requestCount = requestCount;
		this.toolCount = toolCount;
		this.uptimeText = uptimeText;
		this.lastError = lastError;
		this.clientConfigJson = clientConfigJson;
	}

	public static McpStatusSnapshot capture(McpServerManager manager, McpOptions options) {
		return capture(manager, options, ClientFormat.MCP_SERVERS, Instant.now());
	}

	public static McpStatusSnapshot capture(McpServerManager manager, McpOptions options, ClientFormat format) {
		return capture(manager, options, format, Instant.now());
	}

	static McpStatusSnapshot capture(McpServerManager manager, McpOptions options, Instant now) {
		return capture(manager, options, ClientFormat.MCP_SERVERS, now);
	}

	static McpStatusSnapshot capture(McpServerManager manager, McpOptions options, ClientFormat format, Instant now) {
		boolean running = manager.isRunning();
		String lastError = manager.getLastError();
		String activeUrl = manager.getActiveUrl();
		String configUrl = activeUrl != null ? activeUrl : "http://127.0.0.1:" + options.getPort() + "/mcp";

		String statusText;
		if (running) {
			statusText = "Running on port " + manager.getActivePort();
		} else if (lastError != null && !lastError.isBlank()) {
			statusText = "Error: " + lastError;
		} else {
			statusText = "Stopped";
		}

		String token = options.getToken();
		String authText = (token != null && !token.trim().isEmpty())
				? "Bearer token enabled"
				: "Disabled (127.0.0.1 loopback only)";

		String uptimeText = formatUptime(manager.getStartedAt(), now);
		ClientFormat resolvedFormat = format == null ? ClientFormat.MCP_SERVERS : format;
		String configJson = GuiTools.buildClientConfigJson(configUrl, token, resolvedFormat);

		return new McpStatusSnapshot(
				running,
				statusText,
				running ? activeUrl : "-",
				authText,
				manager.getRequestCount(),
				manager.getToolCount(),
				uptimeText,
				lastError,
				configJson);
	}

	static String formatUptime(Instant startedAt, Instant now) {
		if (startedAt == null || now == null) {
			return "-";
		}
		long totalSeconds = Math.max(0L, Duration.between(startedAt, now).getSeconds());
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format("%dh %02dm %02ds", hours, minutes, seconds);
		}
		if (minutes > 0) {
			return String.format("%dm %02ds", minutes, seconds);
		}
		return seconds + "s";
	}

	public boolean isRunning() {
		return running;
	}

	public String getStatusText() {
		return statusText;
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public String getAuthText() {
		return authText;
	}

	public long getRequestCount() {
		return requestCount;
	}

	public int getToolCount() {
		return toolCount;
	}

	public String getUptimeText() {
		return uptimeText;
	}

	public String getLastError() {
		return lastError;
	}

	public String getClientConfigJson() {
		return clientConfigJson;
	}
}
