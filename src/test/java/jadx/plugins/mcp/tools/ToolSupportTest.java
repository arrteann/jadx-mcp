package jadx.plugins.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSupportTest {

	@Test
	void filtersLibrariesGeneratedAndCustomPrefixes() {
		JsonObject args = new JsonObject();
		assertThat(ToolSupport.shouldSkipClass("androidx.appcompat.app.AppCompatActivity", args)).isFalse();
		assertThat(ToolSupport.shouldSkipClass("com.example.R$id", args)).isFalse();

		args.addProperty("excludeLibraries", true);
		assertThat(ToolSupport.shouldSkipClass("androidx.appcompat.app.AppCompatActivity", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("kotlin.collections.CollectionsKt", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("com.example.MainActivity", args)).isFalse();

		args.addProperty("excludeGenerated", true);
		assertThat(ToolSupport.shouldSkipClass("com.example.R", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("com.example.R$layout", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("com.example.BuildConfig", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("com.example.MainActivity", args)).isFalse();

		args.addProperty("excludePrefixes", "com.example.analytics., com.vendor.");
		assertThat(ToolSupport.shouldSkipClass("com.example.analytics.Tracker", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("com.vendor.Sdk", args)).isTrue();
		assertThat(ToolSupport.shouldSkipClass("com.example.MainActivity", args)).isFalse();
	}
}
