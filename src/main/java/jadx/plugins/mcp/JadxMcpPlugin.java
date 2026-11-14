package jadx.plugins.mcp;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;
import jadx.plugins.mcp.tools.DecompileTools;
import jadx.plugins.mcp.tools.GuiTools;
import jadx.plugins.mcp.tools.HierarchyTools;
import jadx.plugins.mcp.tools.ProjectTools;
import jadx.plugins.mcp.tools.ResourceTools;
import jadx.plugins.mcp.tools.SearchTools;
import jadx.plugins.mcp.tools.XrefTools;

/**
 * Entry point for the jadx-mcp plugin.
 */
public final class JadxMcpPlugin implements JadxPlugin {

	public static final String PLUGIN_ID = "jadx-mcp";
	public static final String VERSION = "0.1.0";

	private static final Logger LOG = LoggerFactory.getLogger(JadxMcpPlugin.class);

	private final McpOptions options = new McpOptions();
	private final McpServerManager serverManager = new McpServerManager(VERSION);
	private boolean initialized = false;

	public JadxMcpPlugin() {
		ProjectTools.register(serverManager.getRegistry(), serverManager);
		DecompileTools.register(serverManager.getRegistry(), serverManager, options);
		XrefTools.register(serverManager.getRegistry(), serverManager);
		HierarchyTools.register(serverManager.getRegistry(), serverManager);
		SearchTools.register(serverManager.getRegistry(), serverManager);
		ResourceTools.register(serverManager.getRegistry(), serverManager, options);
		GuiTools.register(serverManager.getRegistry(), serverManager);
	}

	@Override
	public JadxPluginInfo getPluginInfo() {
		return JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
				.name("JADX MCP Server")
				.description("Lightweight embedded MCP server for AI-assisted reverse engineering")
				.homepage("https://github.com/arrteann/jadx-mcp")
				.build();
	}

	@Override
	public void init(JadxPluginContext context) {
		context.registerOptions(options);
		serverManager.setDecompiler(context.getDecompiler());
		serverManager.setGuiContext(context.getGuiContext());
		GuiTools.registerMenuAction(context.getGuiContext(), serverManager, options);
		options.setChangeListener(this::syncServerState);
		initialized = true;
		syncServerState();
	}

	@Override
	public void unload() {
		initialized = false;
		options.setChangeListener(null);
		serverManager.shutdown();
	}

	public McpOptions getOptions() {
		return options;
	}

	public McpServerManager getServerManager() {
		return serverManager;
	}

	private void syncServerState() {
		if (!initialized) {
			return;
		}
		if (!options.isEnabled()) {
			serverManager.setLastError(null);
			serverManager.stop();
			return;
		}
		try {
			serverManager.start(options.getPort(), options.isAutoPortFallback(), options.getToken());
		} catch (IOException e) {
			serverManager.setLastError(e.getMessage());
			LOG.error("Failed to start JADX MCP server on port {}: {}", options.getPort(), e.getMessage());
		}
	}
}
