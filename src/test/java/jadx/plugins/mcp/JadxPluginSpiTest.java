package jadx.plugins.mcp;

import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginInfo;

import static org.assertj.core.api.Assertions.assertThat;

class JadxPluginSpiTest {

	@Test
	void discoversPluginViaServiceLoaderAndProvidesMetadata() {
		ServiceLoader<JadxPlugin> loader = ServiceLoader.load(JadxPlugin.class);
		JadxPlugin found = null;
		for (JadxPlugin plugin : loader) {
			if (plugin instanceof JadxMcpPlugin) {
				found = plugin;
				break;
			}
		}
		assertThat(found).isNotNull();

		JadxPluginInfo info = found.getPluginInfo();
		assertThat(info.getPluginId()).isEqualTo(JadxMcpPlugin.PLUGIN_ID);
		assertThat(info.getName()).isEqualTo("JADX MCP Server");
	}
}
