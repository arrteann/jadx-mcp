package jadx.plugins.mcp.mcp;

/**
 * Typed JSON-RPC / MCP protocol exception carrying a standard error code.
 */
public class McpException extends RuntimeException {

	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	private final int code;

	public McpException(int code, String message) {
		super(message);
		this.code = code;
	}

	public McpException(int code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public int getCode() {
		return code;
	}
}
