package jadx.plugins.mcp.fixtures;

/**
 * Child fixture class for hierarchy, override, xref, and sink call-site tests.
 * Methods are only inspected statically by JADX and never invoked at runtime.
 */
public class SampleChild extends SampleTarget {

	@Override
	public void run() {
		System.out.println(greet("child"));
	}

	public void dangerDemo(String cmd) throws Exception {
		Runtime.getRuntime().exec(cmd);
	}
}
