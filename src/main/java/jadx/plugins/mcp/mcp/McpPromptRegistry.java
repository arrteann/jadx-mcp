package jadx.plugins.mcp.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Thread-safe registry for built-in MCP prompts.
 */
public final class McpPromptRegistry {

	public interface McpPrompt {
		String getName();

		String getDescription();

		JsonArray getArguments();

		String render(JsonObject arguments);
	}

	private final Map<String, McpPrompt> prompts = new ConcurrentHashMap<>();
	private final List<String> order = Collections.synchronizedList(new ArrayList<>());

	public void register(McpPrompt prompt) {
		if (prompt == null || prompt.getName() == null || prompt.getName().trim().isEmpty()) {
			throw new IllegalArgumentException("Prompt and prompt name must not be blank");
		}
		String name = prompt.getName();
		if (prompts.put(name, prompt) == null) {
			order.add(name);
		}
	}

	public McpPrompt get(String name) {
		return name == null ? null : prompts.get(name);
	}

	public List<McpPrompt> listAll() {
		List<McpPrompt> result = new ArrayList<>();
		synchronized (order) {
			for (String name : order) {
				McpPrompt prompt = prompts.get(name);
				if (prompt != null) {
					result.add(prompt);
				}
			}
		}
		return Collections.unmodifiableList(result);
	}
}
