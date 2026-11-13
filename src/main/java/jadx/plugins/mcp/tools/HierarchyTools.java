package jadx.plugins.mcp.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.args.ArgType;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;

/**
 * Class inheritance hierarchy and method override resolution tools.
 */
public final class HierarchyTools {

	private HierarchyTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager) {
		registry.register(new GetTypeHierarchyTool(manager));
		registry.register(new GetOverridesTool(manager));
	}

	private static final class GetTypeHierarchyTool implements McpTool {
		private final McpServerManager manager;

		private GetTypeHierarchyTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_type_hierarchy";
		}

		@Override
		public String getDescription() {
			return "Return superclass, implemented interfaces, and known subclasses/implementors of a class.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("className", ToolSupport.prop("string", "Full class name"));
			return ToolSupport.schema(props, "className");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			JavaClass cls = ToolSupport.requireClass(decompiler, ToolSupport.getString(arguments, "className", ""));

			ArgType superType = cls.getSuperClass();
			List<String> interfaces = new ArrayList<>();
			for (ArgType iface : cls.getInterfaces()) {
				interfaces.add(iface.toString());
			}

			String targetFull = cls.getFullName();
			String targetRaw = cls.getRawName();
			List<String> subclasses = new ArrayList<>();
			for (JavaClass candidate : decompiler.getClassesWithInners()) {
				if (candidate.equals(cls)) {
					continue;
				}
				if (matchesType(candidate.getSuperClass(), targetFull, targetRaw)
						|| matchesAny(candidate.getInterfaces(), targetFull, targetRaw)) {
					subclasses.add(candidate.getFullName());
				}
			}

			JsonObject out = new JsonObject();
			out.addProperty("className", targetFull);
			out.addProperty("superClass", superType == null ? "" : superType.toString());
			out.add("interfaces", ToolSupport.toJsonArray(interfaces));
			out.add("subclasses", ToolSupport.toJsonArray(subclasses));
			return out;
		}

		private static boolean matchesAny(List<ArgType> types, String fullName, String rawName) {
			for (ArgType type : types) {
				if (matchesType(type, fullName, rawName)) {
					return true;
				}
			}
			return false;
		}

		private static boolean matchesType(ArgType type, String fullName, String rawName) {
			if (type == null || !type.isObject()) {
				return false;
			}
			String obj = type.getObject();
			return fullName.equals(obj) || rawName.equals(obj);
		}
	}

	private static final class GetOverridesTool implements McpTool {
		private final McpServerManager manager;

		private GetOverridesTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_overrides";
		}

		@Override
		public String getDescription() {
			return "Find all override-related implementations of a method across subclasses and interfaces.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("className", ToolSupport.prop("string", "Declaring class name"));
			props.add("methodName", ToolSupport.prop("string", "Method name"));
			props.add("shortId", ToolSupport.prop("string", "Optional method shortId"));
			return ToolSupport.schema(props, "className");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			JavaClass cls = ToolSupport.requireClass(decompiler, ToolSupport.getString(arguments, "className", ""));
			String methodName = ToolSupport.getString(arguments, "methodName", "").trim();
			String shortId = ToolSupport.getString(arguments, "shortId", "").trim();

			JavaMethod target = findMethod(cls, methodName, shortId);
			JsonArray overrides = new JsonArray();
			for (JavaMethod related : target.getOverrideRelatedMethods()) {
				if (related.equals(target)) {
					continue;
				}
				JsonObject item = new JsonObject();
				item.addProperty("className", related.getDeclaringClass().getFullName());
				item.addProperty("methodName", related.getName());
				item.addProperty("shortId", related.getShortId());
				overrides.add(item);
			}

			JsonObject out = new JsonObject();
			out.addProperty("className", cls.getFullName());
			out.addProperty("methodName", target.getName());
			out.addProperty("shortId", target.getShortId());
			out.add("overrides", overrides);
			return out;
		}

		private static JavaMethod findMethod(JavaClass cls, String methodName, String shortId) {
			for (JavaMethod mth : cls.getMethods()) {
				if (!shortId.isEmpty() && shortId.equals(mth.getShortId())) {
					return mth;
				}
				if (shortId.isEmpty() && !methodName.isEmpty() && methodName.equals(mth.getName())) {
					return mth;
				}
			}
			throw new IllegalArgumentException("Method not found in " + cls.getFullName());
		}
	}
}
