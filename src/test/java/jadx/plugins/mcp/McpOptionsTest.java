package jadx.plugins.mcp;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jadx.api.plugins.options.OptionDescription;
import jadx.api.plugins.options.OptionFlag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOptionsTest {

	@Test
	void providesExpectedDefaultsAndNotChangingCodeFlags() {
		McpOptions options = new McpOptions();

		assertThat(options.isEnabled()).isTrue();
		assertThat(options.getPort()).isEqualTo(McpOptions.DEFAULT_PORT);
		assertThat(options.isAutoPortFallback()).isTrue();
		assertThat(options.getToken()).isEmpty();
		assertThat(options.getMaxOutputChars()).isEqualTo(32_000);

		for (OptionDescription desc : options.getOptionsDescriptions()) {
			assertThat(desc.getFlags()).contains(OptionFlag.NOT_CHANGING_CODE);
		}
	}

	@Test
	void parsesCustomOptionsAndValidatesPortBounds() {
		McpOptions options = new McpOptions();
		Map<String, String> values = new HashMap<>();
		values.put(McpOptions.OPT_ENABLED, "no");
		values.put(McpOptions.OPT_PORT, "9010");
		values.put(McpOptions.OPT_AUTO_PORT_FALLBACK, "no");
		values.put(McpOptions.OPT_TOKEN, "  my-secret  ");
		values.put(McpOptions.OPT_MAX_CHARS, "500"); // clamped to >= 1000

		options.setOptions(values);

		assertThat(options.isEnabled()).isFalse();
		assertThat(options.getPort()).isEqualTo(9010);
		assertThat(options.isAutoPortFallback()).isFalse();
		assertThat(options.getToken()).isEqualTo("my-secret");
		assertThat(options.getMaxOutputChars()).isEqualTo(1000);

		Map<String, String> invalid = new HashMap<>();
		invalid.put(McpOptions.OPT_PORT, "70000");
		assertThatThrownBy(() -> options.setOptions(invalid))
				.isInstanceOf(RuntimeException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}
}
