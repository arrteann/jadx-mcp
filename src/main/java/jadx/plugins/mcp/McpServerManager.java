package jadx.plugins.mcp;

import java.io.IOException;
import java.net.BindException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.plugins.mcp.mcp.McpJsonRpcHandler;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.server.HttpSecurityValidator;
import jadx.plugins.mcp.server.MicroHttpServer;

/**
 * Manages the lifecycle of the embedded MCP server and the active {@link JadxDecompiler} reference.
 */
public final class McpServerManager {

	private static final Logger LOG = LoggerFactory.getLogger(McpServerManager.class);
	private static final int MAX_PORT_ATTEMPTS = 20;

	private final McpToolRegistry registry = new McpToolRegistry();
	private final AtomicReference<JadxDecompiler> decompilerRef = new AtomicReference<>();
	private final AtomicReference<JadxGuiContext> guiContextRef = new AtomicReference<>();
	private final String serverVersion;

	private MicroHttpServer server;
	private Instant startedAt;
	private volatile String lastError;

	public McpServerManager(String serverVersion) {
		this.serverVersion = serverVersion;
	}

	public McpToolRegistry getRegistry() {
		return registry;
	}

	public void setDecompiler(JadxDecompiler decompiler) {
		decompilerRef.set(decompiler);
	}

	public JadxDecompiler getDecompiler() {
		return decompilerRef.get();
	}

	public void setGuiContext(JadxGuiContext guiContext) {
		guiContextRef.set(guiContext);
	}

	public JadxGuiContext getGuiContext() {
		return guiContextRef.get();
	}

	public synchronized void start(int preferredPort, boolean autoFallback, String token) throws IOException {
		stopServerOnly();
		McpJsonRpcHandler rpc = new McpJsonRpcHandler(registry, JadxMcpPlugin.PLUGIN_ID, serverVersion);
		HttpSecurityValidator security = new HttpSecurityValidator(token);

		MicroHttpServer created = bindWithFallback(preferredPort, autoFallback, rpc, security);
		created.start();
		this.server = created;
		this.startedAt = Instant.now();
		this.lastError = null;
		LOG.info("JADX MCP server listening on {}", getActiveUrl());
	}

	private static MicroHttpServer bindWithFallback(int preferredPort, boolean autoFallback,
			McpJsonRpcHandler rpc, HttpSecurityValidator security) throws IOException {
		if (!autoFallback || preferredPort == 0) {
			return new MicroHttpServer(preferredPort, rpc, security);
		}
		IOException lastError = null;
		int maxPort = Math.min(65535, preferredPort + MAX_PORT_ATTEMPTS - 1);
		for (int port = preferredPort; port <= maxPort; port++) {
			try {
				return new MicroHttpServer(port, rpc, security);
			} catch (BindException e) {
				lastError = e;
				LOG.debug("Port {} is busy, trying next port", port);
			}
		}
		throw lastError != null ? lastError : new BindException("No free port available starting at " + preferredPort);
	}

	public synchronized void stop() {
		stopServerOnly();
	}

	public synchronized void shutdown() {
		stopServerOnly();
		decompilerRef.set(null);
		guiContextRef.set(null);
	}

	private void stopServerOnly() {
		if (server != null) {
			server.close();
			server = null;
			startedAt = null;
			LOG.info("JADX MCP server stopped");
		}
	}

	public synchronized boolean isRunning() {
		return server != null;
	}

	public synchronized int getActivePort() {
		return server == null ? -1 : server.getPort();
	}

	public synchronized String getActiveUrl() {
		return server == null ? null : "http://127.0.0.1:" + server.getPort() + "/mcp";
	}

	public synchronized long getRequestCount() {
		return server == null ? 0L : server.getRequestCount();
	}

	public synchronized Instant getStartedAt() {
		return startedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError;
	}

	public int getToolCount() {
		return registry.listAll().size();
	}

	public String getServerVersion() {
		return serverVersion;
	}
}
