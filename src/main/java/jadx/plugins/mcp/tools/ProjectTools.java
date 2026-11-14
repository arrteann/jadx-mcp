package jadx.plugins.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.core.dex.instructions.args.ArgType;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.util.PaginationHelper;
import jadx.plugins.mcp.util.PaginationHelper.PageResult;

/**
 * Project inspection and class listing MCP tools.
 */
public final class ProjectTools {

	private ProjectTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager) {
		registry.register(new GetProjectInfoTool(manager));
		registry.register(new ListClassesTool(manager));
		registry.register(new GetPackageSummaryTool(manager));
	}

	private static final class GetProjectInfoTool implements McpTool {
		private final McpServerManager manager;

		private GetProjectInfoTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_project_info";
		}

		@Override
		public String getDescription() {
			return "Return loaded project summary including class/method counts, input files, and native libraries.";
		}

		@Override
		public JsonObject getInputSchema() {
			return ToolSupport.schema(new JsonObject());
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = manager.getDecompiler();
			JsonObject out = new JsonObject();
			if (decompiler == null) {
				out.addProperty("ready", false);
				return out;
			}

			List<JavaClass> classes = decompiler.getClassesWithInners();
			int methodCount = 0;
			int fieldCount = 0;
			for (JavaClass cls : classes) {
				methodCount += cls.getMethods().size();
				fieldCount += cls.getFields().size();
			}

			JsonArray nativeLibs = new JsonArray();
			List<ResourceFile> resources = decompiler.getResources();
			for (ResourceFile res : resources) {
				String name = res.getOriginalName();
				if (name != null && name.startsWith("lib/") && name.endsWith(".so")) {
					nativeLibs.add(name);
				}
			}

			JsonArray inputs = new JsonArray();
			for (File file : decompiler.getArgs().getInputFiles()) {
				inputs.add(file.getName());
			}

			out.addProperty("ready", true);
			out.addProperty("classCount", classes.size());
			out.addProperty("methodCount", methodCount);
			out.addProperty("fieldCount", fieldCount);
			out.addProperty("resourceCount", resources.size());
			out.add("nativeLibs", nativeLibs);
			out.add("inputFiles", inputs);
			return out;
		}
	}

	private static final class ListClassesTool implements McpTool {
		private final McpServerManager manager;

		private ListClassesTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "list_classes";
		}

		@Override
		public String getDescription() {
			return "List classes in the loaded project with optional package prefix, query filter, and pagination.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("packagePrefix", ToolSupport.prop("string", "Optional package prefix filter"));
			props.add("query", ToolSupport.prop("string", "Optional case-insensitive substring filter"));
			ToolSupport.addClassFilterProps(props);
			props.add("offset", ToolSupport.prop("integer", "Pagination offset (default 0)"));
			props.add("limit", ToolSupport.prop("integer", "Page size (default 50, max 500)"));
			return ToolSupport.schema(props);
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String pkgPrefix = ToolSupport.getString(arguments, "packagePrefix", "").trim();
			String query = ToolSupport.getString(arguments, "query", "").trim().toLowerCase(Locale.ROOT);
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			List<String> matched = new ArrayList<>();
			for (JavaClass cls : decompiler.getClasses()) {
				String name = cls.getFullName();
				if (ToolSupport.shouldSkipClass(name, arguments)) {
					continue;
				}
				if (!pkgPrefix.isEmpty() && !name.startsWith(pkgPrefix)) {
					continue;
				}
				if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)) {
					continue;
				}
				matched.add(name);
			}
			Collections.sort(matched);

			PageResult<String> page = PaginationHelper.paginate(matched, offset, limit);
			JsonObject out = new JsonObject();
			out.add("items", ToolSupport.toJsonArray(page.getItems()));
			out.addProperty("offset", page.getOffset());
			out.addProperty("limit", page.getLimit());
			out.addProperty("total", page.getTotal());
			out.addProperty("hasMore", page.isHasMore());
			out.addProperty("nextOffset", page.getNextOffset());
			return out;
		}
	}

	private static final class GetPackageSummaryTool implements McpTool {
		private final McpServerManager manager;

		private GetPackageSummaryTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_package_summary";
		}

		@Override
		public String getDescription() {
			return "Summarize classes, method/field counts, superclasses, and subpackages under a package prefix without decompiling.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("packagePrefix", ToolSupport.prop("string", "Package prefix (e.g. com.example.auth)"));
			ToolSupport.addClassFilterProps(props);
			props.add("offset", ToolSupport.prop("integer", "Pagination offset for classes (default 0)"));
			props.add("limit", ToolSupport.prop("integer", "Page size for classes (default 50, max 500)"));
			return ToolSupport.schema(props, "packagePrefix");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String pkgPrefix = ToolSupport.getString(arguments, "packagePrefix", "").trim();
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			List<JavaClass> matched = new ArrayList<>();
			Set<String> subpackages = new TreeSet<>();
			int totalMethods = 0;
			int totalFields = 0;

			for (JavaClass cls : decompiler.getClasses()) {
				String fullName = cls.getFullName();
				if (ToolSupport.shouldSkipClass(fullName, arguments)) {
					continue;
				}
				if (!pkgPrefix.isEmpty() && !fullName.startsWith(pkgPrefix)) {
					continue;
				}
				matched.add(cls);
				totalMethods += cls.getMethods().size();
				totalFields += cls.getFields().size();
				String clsPkg = cls.getPackage();
				if (clsPkg != null && !clsPkg.isEmpty() && !clsPkg.equals(pkgPrefix)) {
					subpackages.add(clsPkg);
				}
			}
			matched.sort(Comparator.comparing(JavaClass::getFullName));

			PageResult<JavaClass> page = PaginationHelper.paginate(matched, offset, limit);
			JsonArray classesArr = new JsonArray();
			for (JavaClass cls : page.getItems()) {
				JsonObject entry = new JsonObject();
				entry.addProperty("className", cls.getFullName());
				ArgType superType = cls.getSuperClass();
				if (superType != null) {
					entry.addProperty("superClass", superType.toString());
				}
				entry.addProperty("methodCount", cls.getMethods().size());
				entry.addProperty("fieldCount", cls.getFields().size());
				classesArr.add(entry);
			}

			JsonObject out = new JsonObject();
			out.addProperty("packagePrefix", pkgPrefix);
			out.addProperty("classCount", matched.size());
			out.addProperty("totalMethods", totalMethods);
			out.addProperty("totalFields", totalFields);
			out.add("subpackages", ToolSupport.toJsonArray(new ArrayList<>(subpackages)));
			out.add("classes", classesArr);
			out.addProperty("offset", page.getOffset());
			out.addProperty("limit", page.getLimit());
			out.addProperty("hasMore", page.isHasMore());
			out.addProperty("nextOffset", page.getNextOffset());
			return out;
		}
	}
}
