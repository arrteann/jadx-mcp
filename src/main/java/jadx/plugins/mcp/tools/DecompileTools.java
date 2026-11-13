package jadx.plugins.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.DecompilationMode;
import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.args.ArgType;
import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.util.PaginationHelper;
import jadx.plugins.mcp.util.PaginationHelper.LineSliceResult;

/**
 * Class outline, class source, and method source MCP tools.
 */
public final class DecompileTools {

	private DecompileTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager, McpOptions options) {
		registry.register(new GetClassOutlineTool(manager));
		registry.register(new GetClassSourceTool(manager, options));
		registry.register(new GetMethodSourceTool(manager, options));
	}

	static JsonObject buildOutline(JavaClass cls) {
		JsonObject out = new JsonObject();
		out.addProperty("className", cls.getFullName());
		out.addProperty("rawName", cls.getRawName());
		out.addProperty("packageName", cls.getPackage());

		ArgType superType = cls.getSuperClass();
		out.addProperty("superClass", superType == null ? "" : superType.toString());

		JsonArray interfaces = new JsonArray();
		for (ArgType iface : cls.getInterfaces()) {
			interfaces.add(iface.toString());
		}
		out.add("interfaces", interfaces);

		JsonArray fields = new JsonArray();
		for (JavaField field : cls.getFields()) {
			JsonObject f = new JsonObject();
			f.addProperty("name", field.getName());
			f.addProperty("type", field.getType().toString());
			f.addProperty("shortId", field.getShortId());
			fields.add(f);
		}
		out.add("fields", fields);

		JsonArray methods = new JsonArray();
		for (JavaMethod mth : cls.getMethods()) {
			methods.add(buildMethodSummary(mth));
		}
		out.add("methods", methods);
		return out;
	}

	private static JsonObject buildMethodSummary(JavaMethod mth) {
		JsonObject m = new JsonObject();
		m.addProperty("name", mth.getName());
		m.addProperty("shortId", mth.getShortId());
		m.addProperty("returnType", mth.getReturnType().toString());
		JsonArray argTypes = new JsonArray();
		for (ArgType arg : mth.getArguments()) {
			argTypes.add(arg.toString());
		}
		m.add("argTypes", argTypes);
		return m;
	}

	private static final class GetClassOutlineTool implements McpTool {
		private final McpServerManager manager;

		private GetClassOutlineTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_class_outline";
		}

		@Override
		public String getDescription() {
			return "Return lightweight class structure (superclass, interfaces, fields, and method signatures with shortIds) without full decompilation.";
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
			return buildOutline(cls);
		}
	}

	private static final class GetClassSourceTool implements McpTool {
		private final McpServerManager manager;
		private final McpOptions options;

		private GetClassSourceTool(McpServerManager manager, McpOptions options) {
			this.manager = manager;
			this.options = options;
		}

		@Override
		public String getName() {
			return "get_class_source";
		}

		@Override
		public String getDescription() {
			return "Decompile a class in normal, simple, fallback, or smali mode with optional line slicing and automatic outline fallback on large classes.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("className", ToolSupport.prop("string", "Full class name"));
			props.add("mode", ToolSupport.prop("string", "Decompile mode: normal, simple, fallback, or smali"));
			props.add("startLine", ToolSupport.prop("integer", "1-based start line"));
			props.add("endLine", ToolSupport.prop("integer", "1-based end line"));
			props.add("maxChars", ToolSupport.prop("integer", "Maximum character budget"));
			return ToolSupport.schema(props, "className");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			JavaClass cls = ToolSupport.requireClass(decompiler, ToolSupport.getString(arguments, "className", ""));
			String mode = ToolSupport.getString(arguments, "mode", "normal").toLowerCase(Locale.ROOT);
			Integer startLine = ToolSupport.getNullableInt(arguments, "startLine");
			Integer endLine = ToolSupport.getNullableInt(arguments, "endLine");
			int maxChars = ToolSupport.getInt(arguments, "maxChars", options.getMaxOutputChars());

			String code = renderClassCode(cls, mode);
			boolean hasLineRange = startLine != null || endLine != null;
			if (!hasLineRange && maxChars > 0 && code.length() > maxChars) {
				JsonObject out = new JsonObject();
				out.addProperty("className", cls.getFullName());
				out.addProperty("mode", mode);
				out.addProperty("outlineFallback", true);
				out.addProperty("totalChars", code.length());
				out.add("outline", buildOutline(cls));
				return out;
			}

			LineSliceResult slice = PaginationHelper.sliceLines(code, startLine, endLine, maxChars);
			JsonObject out = new JsonObject();
			out.addProperty("className", cls.getFullName());
			out.addProperty("mode", mode);
			out.addProperty("outlineFallback", false);
			out.addProperty("startLine", slice.getStartLine());
			out.addProperty("endLine", slice.getEndLine());
			out.addProperty("totalLines", slice.getTotalLines());
			out.addProperty("truncated", slice.isTruncated());
			out.addProperty("code", slice.getContent());
			return out;
		}

		private static String renderClassCode(JavaClass cls, String mode) {
			switch (mode) {
				case "smali":
					String smali = cls.getSmali();
					return smali == null ? "" : smali;
				case "simple":
					return safeCodeStr(cls.decompileWithMode(DecompilationMode.SIMPLE));
				case "fallback":
					return safeCodeStr(cls.decompileWithMode(DecompilationMode.FALLBACK));
				case "normal":
				default:
					return safeCodeStr(cls.decompileWithMode(DecompilationMode.AUTO));
			}
		}

		private static String safeCodeStr(ICodeInfo info) {
			return info == null || info.getCodeStr() == null ? "" : info.getCodeStr();
		}
	}

	private static final class GetMethodSourceTool implements McpTool {
		private final McpServerManager manager;
		private final McpOptions options;

		private GetMethodSourceTool(McpServerManager manager, McpOptions options) {
			this.manager = manager;
			this.options = options;
		}

		@Override
		public String getName() {
			return "get_method_source";
		}

		@Override
		public String getDescription() {
			return "Return source code for a single method, supporting shortId to disambiguate overloads.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("className", ToolSupport.prop("string", "Full class name"));
			props.add("methodName", ToolSupport.prop("string", "Method name (optional if shortId is provided)"));
			props.add("shortId", ToolSupport.prop("string", "Method shortId for exact overload selection"));
			props.add("maxChars", ToolSupport.prop("integer", "Maximum character budget"));
			return ToolSupport.schema(props, "className");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			JavaClass cls = ToolSupport.requireClass(decompiler, ToolSupport.getString(arguments, "className", ""));
			String shortId = ToolSupport.getString(arguments, "shortId", "").trim();
			String methodName = ToolSupport.getString(arguments, "methodName", "").trim();
			int maxChars = ToolSupport.getInt(arguments, "maxChars", options.getMaxOutputChars());

			List<JavaMethod> matches = findMatchingMethods(cls, methodName, shortId);
			if (matches.isEmpty()) {
				throw new IllegalArgumentException("Method not found in " + cls.getFullName());
			}
			if (matches.size() > 1) {
				JsonObject out = new JsonObject();
				out.addProperty("className", cls.getFullName());
				out.addProperty("ambiguous", true);
				JsonArray candidates = new JsonArray();
				for (JavaMethod m : matches) {
					candidates.add(buildMethodSummary(m));
				}
				out.add("candidates", candidates);
				return out;
			}

			JavaMethod target = matches.get(0);
			LineSliceResult slice = PaginationHelper.sliceLines(target.getCodeStr(), null, null, maxChars);
			JsonObject out = new JsonObject();
			out.addProperty("className", cls.getFullName());
			out.addProperty("ambiguous", false);
			out.addProperty("name", target.getName());
			out.addProperty("shortId", target.getShortId());
			out.addProperty("fullName", target.getFullName());
			out.addProperty("truncated", slice.isTruncated());
			out.addProperty("code", slice.getContent());
			return out;
		}

		private static List<JavaMethod> findMatchingMethods(JavaClass cls, String methodName, String shortId) {
			List<JavaMethod> matches = new ArrayList<>();
			for (JavaMethod mth : cls.getMethods()) {
				if (!shortId.isEmpty()) {
					if (shortId.equals(mth.getShortId())) {
						matches.add(mth);
					}
				} else if (!methodName.isEmpty() && methodName.equals(mth.getName())) {
					matches.add(mth);
				}
			}
			return matches;
		}
	}
}
