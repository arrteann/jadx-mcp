package jadx.plugins.mcp.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for MCP tools preserving registration order.
 */
public final class McpToolRegistry {

	private final Map<String, McpTool> tools = new ConcurrentHashMap<>();
	private final List<String> order = Collections.synchronizedList(new ArrayList<>());

	public void register(McpTool tool) {
		if (tool == null || tool.getName() == null || tool.getName().trim().isEmpty()) {
			throw new IllegalArgumentException("Tool and tool name must not be blank");
		}
		String name = tool.getName();
		if (tools.put(name, tool) == null) {
			order.add(name);
		}
	}

	public McpTool get(String name) {
		return name == null ? null : tools.get(name);
	}

	public List<McpTool> listAll() {
		List<McpTool> result = new ArrayList<>();
		synchronized (order) {
			for (String name : order) {
				McpTool tool = tools.get(name);
				if (tool != null) {
					result.add(tool);
				}
			}
		}
		return Collections.unmodifiableList(result);
	}
}
