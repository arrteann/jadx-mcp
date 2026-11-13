package jadx.plugins.mcp.fixtures;

/**
 * Sample fixture class loaded into {@code JadxDecompiler} during MCP tool tests.
 */
public class SampleTarget implements Runnable {

	private String secretKey = "test-secret";

	@Override
	public void run() {
		System.out.println(greet("world"));
	}

	public String greet() {
		return "hello:" + secretKey;
	}

	public String greet(String name) {
		return "hello:" + name + ":" + secretKey;
	}
}
