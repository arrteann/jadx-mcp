package jadx.plugins.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.core.dex.nodes.MethodNode;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.util.PaginationHelper;
import jadx.plugins.mcp.util.PaginationHelper.PageResult;

/**
 * Cross-reference and raw-bytecode callee analysis tools.
 */
public final class XrefTools {

	private XrefTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager) {
		registry.register(new GetCalleesTool(manager));
		registry.register(new GetXrefsTool(manager));
	}

	private static JavaMethod resolveSingleMethod(JavaClass cls, String methodName, String shortId) {
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
		if (matches.isEmpty()) {
			throw new IllegalArgumentException("Method not found in " + cls.getFullName());
		}
		return matches.get(0);
	}

	private static final class GetCalleesTool implements McpTool {
		private final McpServerManager manager;

		private GetCalleesTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_callees";
		}

		@Override
		public String getDescription() {
			return "List all internal and external methods called by a method directly from raw bytecode.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("className", ToolSupport.prop("string", "Full class name"));
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
			JavaMethod target = resolveSingleMethod(cls, methodName, shortId);

			Map<String, JsonObject> callees = new LinkedHashMap<>();
			MethodNode mthNode = target.getMethodNode();
			ICodeReader codeReader = mthNode.isNoCode() ? null : mthNode.getCodeReader();
			if (codeReader != null) {
				codeReader.visitInstructions(insn -> {
					IMethodRef ref = ToolSupport.readMethodRef(insn);
					if (ref == null) {
						return;
					}
					List<String> argTypes = ref.getArgTypes();
					String key = ref.getParentClassType() + "->" + ref.getName() + ref.getReturnType() + argTypes;
					if (!callees.containsKey(key)) {
						JsonObject entry = new JsonObject();
						entry.addProperty("className", ref.getParentClassType());
						entry.addProperty("methodName", ref.getName());
						entry.addProperty("returnType", String.valueOf(ref.getReturnType()));
						entry.addProperty("argCount", argTypes == null ? 0 : argTypes.size());
						callees.put(key, entry);
					}
				});
			}

			JsonObject out = new JsonObject();
			out.addProperty("className", cls.getFullName());
			out.addProperty("methodName", target.getName());
			out.addProperty("shortId", target.getShortId());
			out.add("callees", toArray(callees));
			return out;
		}
	}

	private static final class GetXrefsTool implements McpTool {
		private final McpServerManager manager;

		private GetXrefsTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_xrefs";
		}

		@Override
		public String getDescription() {
			return "Find incoming references (xrefs) to a class, method, or field.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("targetType", ToolSupport.prop("string", "Target kind: class, method, or field"));
			props.add("className", ToolSupport.prop("string", "Declaring class name"));
			props.add("memberName", ToolSupport.prop("string", "Method or field name (when targetType is method/field)"));
			props.add("shortId", ToolSupport.prop("string", "Optional shortId for exact member match"));
			props.add("offset", ToolSupport.prop("integer", "Pagination offset"));
			props.add("limit", ToolSupport.prop("integer", "Page size"));
			return ToolSupport.schema(props, "targetType", "className");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String targetType = ToolSupport.getString(arguments, "targetType", "").trim().toLowerCase(Locale.ROOT);
			JavaClass cls = ToolSupport.requireClass(decompiler, ToolSupport.getString(arguments, "className", ""));
			String memberName = ToolSupport.getString(arguments, "memberName", "").trim();
			String shortId = ToolSupport.getString(arguments, "shortId", "").trim();
			int offset = ToolSupport.getInt(arguments, "offset", 0);
			int limit = ToolSupport.getInt(arguments, "limit", PaginationHelper.DEFAULT_LIMIT);

			Set<String> refs = new LinkedHashSet<>();
			switch (targetType) {
				case "class":
					collectUseIn(cls.getUseIn(), refs);
					break;
				case "field":
					JavaField field = resolveField(cls, memberName, shortId);
					collectUseIn(field.getUseIn(), refs);
					break;
				case "method":
					List<JavaMethod> targets = resolveMethods(cls, memberName, shortId);
					for (JavaMethod m : targets) {
						collectUseIn(m.getUseIn(), refs);
					}
					collectRawBytecodeMethodCallers(decompiler, cls, targets, refs);
					break;
				default:
					throw new IllegalArgumentException("Unsupported targetType: " + targetType);
			}

			PageResult<String> page = PaginationHelper.paginate(new ArrayList<>(refs), offset, limit);
			JsonObject out = new JsonObject();
			out.addProperty("targetType", targetType);
			out.addProperty("className", cls.getFullName());
			out.add("xrefs", ToolSupport.toJsonArray(page.getItems()));
			out.addProperty("total", page.getTotal());
			out.addProperty("hasMore", page.isHasMore());
			return out;
		}

		private static void collectUseIn(List<JavaNode> nodes, Set<String> out) {
			for (JavaNode node : nodes) {
				out.add(node.getFullName());
			}
		}

		private static void collectRawBytecodeMethodCallers(JadxDecompiler decompiler, JavaClass targetCls,
				List<JavaMethod> targets, Set<String> refs) {
			String rawInternal = "L" + targetCls.getRawName().replace('.', '/') + ";";
			Set<String> targetNames = new LinkedHashSet<>();
			for (JavaMethod m : targets) {
				targetNames.add(m.getName());
			}
			for (JavaClass cls : decompiler.getClassesWithInners()) {
				for (JavaMethod mth : cls.getMethods()) {
					MethodNode mthNode = mth.getMethodNode();
					if (mthNode.isNoCode()) {
						continue;
					}
					ICodeReader reader = mthNode.getCodeReader();
					if (reader == null) {
						continue;
					}
					reader.visitInstructions(insn -> {
						IMethodRef ref = ToolSupport.readMethodRef(insn);
						if (ref == null) {
							return;
						}
						String parent = ref.getParentClassType();
						boolean sameClass = rawInternal.equals(parent) || targetCls.getRawName().equals(parent)
								|| targetCls.getFullName().equals(parent);
						if (sameClass && targetNames.contains(ref.getName())) {
							refs.add(mth.getFullName());
						}
					});
				}
			}
		}

		private static JavaField resolveField(JavaClass cls, String name, String shortId) {
			for (JavaField f : cls.getFields()) {
				if (!shortId.isEmpty() && shortId.equals(f.getShortId())) {
					return f;
				}
				if (!name.isEmpty() && name.equals(f.getName())) {
					return f;
				}
			}
			throw new IllegalArgumentException("Field not found in " + cls.getFullName());
		}

		private static List<JavaMethod> resolveMethods(JavaClass cls, String name, String shortId) {
			List<JavaMethod> list = new ArrayList<>();
			for (JavaMethod m : cls.getMethods()) {
				if (!shortId.isEmpty() && shortId.equals(m.getShortId())) {
					list.add(m);
				} else if (shortId.isEmpty() && !name.isEmpty() && name.equals(m.getName())) {
					list.add(m);
				}
			}
			if (list.isEmpty()) {
				throw new IllegalArgumentException("Method not found in " + cls.getFullName());
			}
			return list;
		}
	}

	private static com.google.gson.JsonArray toArray(Map<String, JsonObject> map) {
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (JsonObject obj : map.values()) {
			arr.add(obj);
		}
		return arr;
	}
}
