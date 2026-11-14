package jadx.plugins.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;
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
}
