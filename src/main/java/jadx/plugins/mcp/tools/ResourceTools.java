package jadx.plugins.mcp.tools;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.core.xmlgen.ResContainer;
import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.util.PaginationHelper;
import jadx.plugins.mcp.util.PaginationHelper.LineSliceResult;
import jadx.plugins.mcp.util.PaginationHelper.PageResult;

/**
 * Resource, AndroidManifest, and attack-surface analysis tools.
 */
public final class ResourceTools {

	private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

	private ResourceTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager, McpOptions options) {
		registry.register(new ListResourcesTool(manager));
		registry.register(new ReadResourceTool(manager, options));
		registry.register(new GetAndroidManifestTool(manager, options));
		registry.register(new GetAttackSurfaceTool(manager));
	}

	private static ResourceFile findManifest(JadxDecompiler decompiler) {
		for (ResourceFile res : decompiler.getResources()) {
			if (res.getType() == ResourceType.MANIFEST || "AndroidManifest.xml".equals(res.getOriginalName())) {
				return res;
			}
		}
		throw new IllegalArgumentException("AndroidManifest.xml not found in loaded resources");
	}

	private static String loadResourceText(ResourceFile res) {
		ResContainer container = res.loadContent();
		if (container == null) {
			return "";
		}
		ICodeInfo text = container.getText();
		if (text != null && text.getCodeStr() != null) {
			return text.getCodeStr();
		}
		return "";
	}

	private static final class ListResourcesTool implements McpTool {
		private final McpServerManager manager;

		private ListResourcesTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "list_resources";
		}

		@Override
		public String getDescription() {
			return "List application resources, assets, and native libraries with optional query/type filters and pagination.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("query", ToolSupport.prop("string", "Optional case-insensitive substring filter"));
			props.add("type", ToolSupport.prop("string", "Optional ResourceType filter (e.g. MANIFEST, XML, IMG)"));
			props.add("offset", ToolSupport.prop("integer", "Pagination offset"));
			props.add("limit", ToolSupport.prop("integer", "Page size"));
			return ToolSupport.schema(props);
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String query = ToolSupport.getString(arguments, "query", "").trim().toLowerCase(Locale.ROOT);
			String typeFilter = ToolSupport.getString(arguments, "type", "").trim().toUpperCase(Locale.ROOT);
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			List<JsonObject> matched = new ArrayList<>();
			for (ResourceFile res : decompiler.getResources()) {
				String name = res.getOriginalName();
				String resType = res.getType() == null ? "UNKNOWN" : res.getType().name();
				if (!query.isEmpty() && (name == null || !name.toLowerCase(Locale.ROOT).contains(query))) {
					continue;
				}
				if (!typeFilter.isEmpty() && !typeFilter.equals(resType)) {
					continue;
				}
				JsonObject item = new JsonObject();
				item.addProperty("name", name);
				item.addProperty("type", resType);
				matched.add(item);
			}

			PageResult<JsonObject> page = PaginationHelper.paginate(matched, offset, limit);
			JsonArray arr = new JsonArray();
			for (JsonObject item : page.getItems()) {
				arr.add(item);
			}
			JsonObject out = new JsonObject();
			out.add("items", arr);
			out.addProperty("offset", page.getOffset());
			out.addProperty("limit", page.getLimit());
			out.addProperty("total", page.getTotal());
			out.addProperty("hasMore", page.isHasMore());
			return out;
		}
	}

	private static final class ReadResourceTool implements McpTool {
		private final McpServerManager manager;
		private final McpOptions options;

		private ReadResourceTool(McpServerManager manager, McpOptions options) {
			this.manager = manager;
			this.options = options;
		}

		@Override
		public String getName() {
			return "read_resource";
		}

		@Override
		public String getDescription() {
			return "Read text content of a resource file by name with optional line range and character budget.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("name", ToolSupport.prop("string", "Exact resource path (e.g. res/values/strings.xml)"));
			props.add("startLine", ToolSupport.prop("integer", "1-based start line"));
			props.add("endLine", ToolSupport.prop("integer", "1-based end line"));
			props.add("maxChars", ToolSupport.prop("integer", "Maximum character budget"));
			return ToolSupport.schema(props, "name");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String name = ToolSupport.getString(arguments, "name", "").trim();
			Integer startLine = ToolSupport.getNullableInt(arguments, "startLine");
			Integer endLine = ToolSupport.getNullableInt(arguments, "endLine");
			int maxChars = ToolSupport.getInt(arguments, "maxChars", options.getMaxOutputChars());

			ResourceFile target = null;
			for (ResourceFile res : decompiler.getResources()) {
				if (name.equals(res.getOriginalName())) {
					target = res;
					break;
				}
			}
			if (target == null) {
				throw new IllegalArgumentException("Resource not found: " + name);
			}

			String content = loadResourceText(target);
			LineSliceResult slice = PaginationHelper.sliceLines(content, startLine, endLine, maxChars);
			JsonObject out = new JsonObject();
			out.addProperty("name", target.getOriginalName());
			out.addProperty("type", target.getType() == null ? "UNKNOWN" : target.getType().name());
			out.addProperty("startLine", slice.getStartLine());
			out.addProperty("endLine", slice.getEndLine());
			out.addProperty("totalLines", slice.getTotalLines());
			out.addProperty("truncated", slice.isTruncated());
			out.addProperty("content", slice.getContent());
			return out;
		}
	}

	private static final class GetAndroidManifestTool implements McpTool {
		private final McpServerManager manager;
		private final McpOptions options;

		private GetAndroidManifestTool(McpServerManager manager, McpOptions options) {
			this.manager = manager;
			this.options = options;
		}

		@Override
		public String getName() {
			return "get_android_manifest";
		}

		@Override
		public String getDescription() {
			return "Return decoded AndroidManifest.xml content with optional line range.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("startLine", ToolSupport.prop("integer", "1-based start line"));
			props.add("endLine", ToolSupport.prop("integer", "1-based end line"));
			props.add("maxChars", ToolSupport.prop("integer", "Maximum character budget"));
			return ToolSupport.schema(props);
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			ResourceFile manifest = findManifest(decompiler);
			String content = loadResourceText(manifest);
			Integer startLine = ToolSupport.getNullableInt(arguments, "startLine");
			Integer endLine = ToolSupport.getNullableInt(arguments, "endLine");
			int maxChars = ToolSupport.getInt(arguments, "maxChars", options.getMaxOutputChars());

			LineSliceResult slice = PaginationHelper.sliceLines(content, startLine, endLine, maxChars);
			JsonObject out = new JsonObject();
			out.addProperty("name", manifest.getOriginalName());
			out.addProperty("startLine", slice.getStartLine());
			out.addProperty("endLine", slice.getEndLine());
			out.addProperty("totalLines", slice.getTotalLines());
			out.addProperty("truncated", slice.isTruncated());
			out.addProperty("content", slice.getContent());
			return out;
		}
	}

	private static final class GetAttackSurfaceTool implements McpTool {
		private static final Set<String> DANGEROUS_PERMISSIONS = Set.of(
				"android.permission.READ_SMS",
				"android.permission.RECEIVE_SMS",
				"android.permission.SEND_SMS",
				"android.permission.RECORD_AUDIO",
				"android.permission.CAMERA",
				"android.permission.ACCESS_FINE_LOCATION",
				"android.permission.ACCESS_COARSE_LOCATION",
				"android.permission.READ_CONTACTS",
				"android.permission.WRITE_EXTERNAL_STORAGE",
				"android.permission.READ_EXTERNAL_STORAGE",
				"android.permission.MANAGE_EXTERNAL_STORAGE",
				"android.permission.REQUEST_INSTALL_PACKAGES",
				"android.permission.SYSTEM_ALERT_WINDOW",
				"android.permission.READ_CALL_LOG",
				"android.permission.PROCESS_OUTGOING_CALLS",
				"android.permission.READ_PHONE_STATE");

		private final McpServerManager manager;

		private GetAttackSurfaceTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_attack_surface";
		}

		@Override
		public String getDescription() {
			return "Parse AndroidManifest.xml to extract permissions, debuggable/backup flags, exported components, and deep links.";
		}

		@Override
		public JsonObject getInputSchema() {
			return ToolSupport.schema(new JsonObject());
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			ResourceFile manifest = findManifest(decompiler);
			String xml = loadResourceText(manifest);
			try {
				return analyzeManifestXml(xml);
			} catch (Exception e) {
				throw new IllegalStateException("Failed to parse AndroidManifest.xml: " + e.getMessage(), e);
			}
		}

		static JsonObject analyzeManifestXml(String xml) throws Exception {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			Document doc = factory.newDocumentBuilder()
					.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

			Element root = doc.getDocumentElement();
			JsonObject out = new JsonObject();
			out.addProperty("packageName", root.getAttribute("package"));

			JsonArray permissions = new JsonArray();
			JsonArray dangerousPermissions = new JsonArray();
			NodeList permNodes = root.getElementsByTagName("uses-permission");
			for (int i = 0; i < permNodes.getLength(); i++) {
				Element el = (Element) permNodes.item(i);
				String name = androidAttr(el, "name");
				if (!name.isEmpty()) {
					permissions.add(name);
					if (DANGEROUS_PERMISSIONS.contains(name)) {
						dangerousPermissions.add(name);
					}
				}
			}
			out.add("permissions", permissions);
			out.add("dangerousPermissions", dangerousPermissions);

			Element appEl = firstChildElement(root, "application");
			out.addProperty("debuggable", appEl != null && "true".equalsIgnoreCase(androidAttr(appEl, "debuggable")));
			out.addProperty("allowBackup", appEl == null || !"false".equalsIgnoreCase(androidAttr(appEl, "allowBackup")));
			out.addProperty("usesCleartextTraffic",
					appEl != null && "true".equalsIgnoreCase(androidAttr(appEl, "usesCleartextTraffic")));
			String netSec = appEl == null ? "" : androidAttr(appEl, "networkSecurityConfig");
			if (!netSec.isEmpty()) {
				out.addProperty("networkSecurityConfig", netSec);
			}

			JsonArray exported = new JsonArray();
			int unprotectedCount = 0;
			if (appEl != null) {
				String[] tags = {"activity", "activity-alias", "service", "receiver", "provider"};
				for (String tag : tags) {
					NodeList nodes = appEl.getElementsByTagName(tag);
					for (int i = 0; i < nodes.getLength(); i++) {
						Element comp = (Element) nodes.item(i);
						JsonObject parsed = parseExportedComponent(tag, comp);
						if (parsed != null) {
							if (!parsed.get("protected").getAsBoolean()) {
								unprotectedCount++;
							}
							exported.add(parsed);
						}
					}
				}
			}
			out.add("exportedComponents", exported);
			out.addProperty("unprotectedExportedCount", unprotectedCount);
			return out;
		}

		private static JsonObject parseExportedComponent(String type, Element comp) {
			String exportedAttr = androidAttr(comp, "exported");
			NodeList filters = comp.getElementsByTagName("intent-filter");
			boolean hasIntentFilter = filters.getLength() > 0;
			boolean isExported = "true".equalsIgnoreCase(exportedAttr)
					|| (exportedAttr.isEmpty() && hasIntentFilter);
			if (!isExported) {
				return null;
			}

			JsonObject obj = new JsonObject();
			obj.addProperty("type", type);
			obj.addProperty("name", androidAttr(comp, "name"));
			String perm = androidAttr(comp, "permission");
			obj.addProperty("protected", !perm.isEmpty());
			if (!perm.isEmpty()) {
				obj.addProperty("permission", perm);
			}
			String auth = androidAttr(comp, "authorities");
			if (!auth.isEmpty()) {
				obj.addProperty("authorities", auth);
			}
			if (hasIntentFilter) {
				obj.add("deepLinks", extractDeepLinks(filters));
			}
			return obj;
		}

		private static JsonArray extractDeepLinks(NodeList filters) {
			JsonArray links = new JsonArray();
			for (int i = 0; i < filters.getLength(); i++) {
				Element filter = (Element) filters.item(i);
				NodeList dataNodes = filter.getElementsByTagName("data");
				for (int j = 0; j < dataNodes.getLength(); j++) {
					Element data = (Element) dataNodes.item(j);
					String scheme = androidAttr(data, "scheme");
					String host = androidAttr(data, "host");
					String path = firstNonEmpty(
							androidAttr(data, "path"),
							androidAttr(data, "pathPrefix"),
							androidAttr(data, "pathPattern"));
					if (!scheme.isEmpty() || !host.isEmpty()) {
						links.add(scheme + "://" + host + path);
					}
				}
			}
			return links;
		}

		private static Element firstChildElement(Element parent, String tag) {
			NodeList list = parent.getElementsByTagName(tag);
			return list.getLength() > 0 ? (Element) list.item(0) : null;
		}

		private static String androidAttr(Element el, String localName) {
			String nsVal = el.getAttributeNS(ANDROID_NS, localName);
			if (nsVal != null && !nsVal.isEmpty()) {
				return nsVal;
			}
			String prefixed = el.getAttribute("android:" + localName);
			return prefixed == null ? "" : prefixed;
		}

		private static String firstNonEmpty(String... values) {
			for (String v : values) {
				if (v != null && !v.isEmpty()) {
					return v;
				}
			}
			return "";
		}
	}
}
