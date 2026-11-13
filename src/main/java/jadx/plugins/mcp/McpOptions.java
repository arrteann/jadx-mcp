package jadx.plugins.mcp;

import java.util.Map;

import jadx.api.plugins.options.OptionFlag;
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;
import jadx.plugins.mcp.util.PaginationHelper;

/**
 * Plugin configuration options exposed in JADX Preferences and CLI.
 */
public final class McpOptions extends BasePluginOptionsBuilder {

	public static final String OPT_ENABLED = JadxMcpPlugin.PLUGIN_ID + ".enabled";
	public static final String OPT_PORT = JadxMcpPlugin.PLUGIN_ID + ".port";
	public static final String OPT_TOKEN = JadxMcpPlugin.PLUGIN_ID + ".token";
	public static final String OPT_MAX_CHARS = JadxMcpPlugin.PLUGIN_ID + ".max-output-chars";
	public static final String OPT_AUTO_PORT_FALLBACK = JadxMcpPlugin.PLUGIN_ID + ".auto-port-fallback";

	public static final int DEFAULT_PORT = 8650;

	private boolean enabled = true;
	private int port = DEFAULT_PORT;
	private String token = "";
	private int maxOutputChars = PaginationHelper.DEFAULT_MAX_CHARS;
	private boolean autoPortFallback = true;

	private Runnable changeListener;

	@Override
	public void registerOptions() {
		boolOption(OPT_ENABLED)
				.description("Enable embedded MCP HTTP server")
				.defaultValue(true)
				.flags(OptionFlag.NOT_CHANGING_CODE)
				.setter(v -> enabled = v);

		intOption(OPT_PORT)
				.description("TCP port for the local MCP server (1024-65535)")
				.defaultValue(DEFAULT_PORT)
				.flags(OptionFlag.NOT_CHANGING_CODE)
				.setter(v -> {
					if (v < 0 || v > 65535) {
						throw new IllegalArgumentException("Port must be between 0 and 65535, got: " + v);
					}
					port = v;
				});

		boolOption(OPT_AUTO_PORT_FALLBACK)
				.description("Automatically pick the next free port if the configured port is busy")
				.defaultValue(true)
				.flags(OptionFlag.NOT_CHANGING_CODE)
				.setter(v -> autoPortFallback = v);

		strOption(OPT_TOKEN)
				.description("Optional Bearer token required in Authorization header")
				.defaultValue("")
				.flags(OptionFlag.NOT_CHANGING_CODE)
				.setter(v -> token = v == null ? "" : v.trim());

		intOption(OPT_MAX_CHARS)
				.description("Maximum characters returned per source response")
				.defaultValue(PaginationHelper.DEFAULT_MAX_CHARS)
				.flags(OptionFlag.NOT_CHANGING_CODE)
				.setter(v -> maxOutputChars = Math.max(1000, v));
	}

	@Override
	public void setOptions(Map<String, String> options) {
		super.setOptions(options);
		if (changeListener != null) {
			changeListener.run();
		}
	}

	public void setChangeListener(Runnable changeListener) {
		this.changeListener = changeListener;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getPort() {
		return port;
	}

	public String getToken() {
		return token;
	}

	public int getMaxOutputChars() {
		return maxOutputChars;
	}

	public boolean isAutoPortFallback() {
		return autoPortFallback;
	}
}
