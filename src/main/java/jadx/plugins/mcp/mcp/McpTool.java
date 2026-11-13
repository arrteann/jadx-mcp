package jadx.plugins.mcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Contract for an MCP tool exposed by jadx-mcp.
 */
public interface McpTool {

	String getName();

	String getDescription();

	JsonObject getInputSchema();

	JsonElement execute(JsonObject arguments);
}
