package jadx.plugins.mcp.tools;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.api.plugins.input.insns.Opcode;
import jadx.core.utils.input.InsnDataUtils;
import jadx.plugins.mcp.McpServerManager;

/**
 * Shared helpers for JADX MCP tool implementations and JSON Schema construction.
 */
public final class ToolSupport {

	private ToolSupport() {
	}

	public static JadxDecompiler requireDecompiler(McpServerManager manager) {
		JadxDecompiler decompiler = manager.getDecompiler();
		if (decompiler == null) {
			throw new IllegalStateException("No active JADX project loaded");
		}
		return decompiler;
	}

	public static JavaClass requireClass(JadxDecompiler decompiler, String className) {
		if (className == null || className.trim().isEmpty()) {
			throw new IllegalArgumentException("Missing required parameter: className");
		}
		String target = className.trim();
		JavaClass cls = decompiler.searchJavaClassByOrigFullName(target);
		if (cls != null) {
			return cls;
		}
		cls = decompiler.searchJavaClassByAliasFullName(target);
		if (cls != null) {
			return cls;
		}
		for (JavaClass item : decompiler.getClasses()) {
			if (target.equals(item.getFullName()) || target.equals(item.getRawName())) {
				return item;
			}
		}
		throw new IllegalArgumentException("Class not found: " + target);
	}

	public static String getString(JsonObject args, String key, String defaultValue) {
		if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
			return defaultValue;
		}
		return args.get(key).getAsString();
	}

	public static int getInt(JsonObject args, String key, int defaultValue) {
		if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
			return defaultValue;
		}
		return args.get(key).getAsInt();
	}

	public static Integer getNullableInt(JsonObject args, String key) {
		if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
			return null;
		}
		return args.get(key).getAsInt();
	}

	public static JsonObject schema(JsonObject properties, String... required) {
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "object");
		obj.add("properties", properties);
		if (required.length > 0) {
			JsonArray req = new JsonArray();
			for (String r : required) {
				req.add(r);
			}
			obj.add("required", req);
		}
		return obj;
	}

	public static JsonObject prop(String type, String description) {
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		obj.addProperty("description", description);
		return obj;
	}

	public static JsonArray toJsonArray(List<String> items) {
		JsonArray arr = new JsonArray();
		for (String item : items) {
			arr.add(item);
		}
		return arr;
	}

	public static IMethodRef readMethodRef(InsnData insn) {
		if (insn.getOpcode() == Opcode.UNKNOWN || insn.getIndexType() != InsnIndexType.METHOD_REF) {
			return null;
		}
		insn.decode();
		return InsnDataUtils.getMethodRef(insn);
	}

	public static String readStringRef(InsnData insn) {
		Opcode opcode = insn.getOpcode();
		if (opcode == Opcode.UNKNOWN) {
			return null;
		}
		if (insn.getIndexType() != InsnIndexType.STRING_REF
				&& opcode != Opcode.CONST_STRING
				&& opcode != Opcode.CONST) {
			return null;
		}
		insn.decode();
		if (insn.getOpcode() == Opcode.CONST_STRING || insn.getIndexType() == InsnIndexType.STRING_REF) {
			return insn.getIndexAsString();
		}
		return null;
	}
}
