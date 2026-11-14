package jadx.plugins.mcp.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thread-safe registry for MCP resources exposed by jadx-mcp.
 */
public final class McpResourceRegistry {

	public static final class McpResource {
		private final String uri;
		private final String name;
		private final String description;
		private final String mimeType;
		private final Supplier<String> reader;

		public McpResource(String uri, String name, String description, String mimeType, Supplier<String> reader) {
			this.uri = uri;
			this.name = name;
			this.description = description;
			this.mimeType = mimeType;
			this.reader = reader;
		}

		public String getUri() {
			return uri;
		}

		public String getName() {
			return name;
		}

		public String getDescription() {
			return description;
		}

		public String getMimeType() {
			return mimeType;
		}

		public String read() {
			return reader.get();
		}
	}

	private final Map<String, McpResource> resources = new ConcurrentHashMap<>();
	private final List<String> order = Collections.synchronizedList(new ArrayList<>());

	public void register(McpResource resource) {
		if (resource == null || resource.getUri() == null || resource.getUri().trim().isEmpty()) {
			throw new IllegalArgumentException("Resource and URI must not be blank");
		}
		String uri = resource.getUri();
		if (resources.put(uri, resource) == null) {
			order.add(uri);
		}
	}

	public McpResource get(String uri) {
		return uri == null ? null : resources.get(uri);
	}

	public List<McpResource> listAll() {
		List<McpResource> result = new ArrayList<>();
		synchronized (order) {
			for (String uri : order) {
				McpResource res = resources.get(uri);
				if (res != null) {
					result.add(res);
				}
			}
		}
		return Collections.unmodifiableList(result);
	}
}
