package jadx.plugins.mcp.tools;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.plugins.mcp.JadxMcpPlugin;
import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.mcp.McpTool;
import jadx.plugins.mcp.mcp.McpToolRegistry;
import jadx.plugins.mcp.ui.McpStatusDialog;

/**
 * GUI context, navigation, rename, and one-click client config copy tools.
 */
public final class GuiTools {

	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private GuiTools() {
	}

	public static void register(McpToolRegistry registry, McpServerManager manager) {
		registry.register(new GetCurrentContextTool(manager));
		registry.register(new NavigateToTool(manager));
		registry.register(new RenameNodeTool(manager));
	}

	public static void registerMenuAction(JadxGuiContext guiContext, McpServerManager manager, McpOptions options) {
		if (guiContext == null) {
			return;
		}
		guiContext.addMenuAction("JADX MCP Server...", () -> McpStatusDialog.show(guiContext, manager, options));
		guiContext.addMenuAction("Copy MCP Server Config", () -> {
			String url = manager.getActiveUrl();
			if (url == null) {
				url = "http://127.0.0.1:" + options.getPort() + "/mcp";
			}
			guiContext.copyToClipboard(buildAgentConfigJson(url, options.getToken()));
		});
	}

	public static String buildAgentConfigJson(String url, String token) {
		JsonObject server = new JsonObject();
		server.addProperty("url", url);
		if (token != null && !token.trim().isEmpty()) {
			JsonObject headers = new JsonObject();
			headers.addProperty("Authorization", "Bearer " + token.trim());
			server.add("headers", headers);
		}
		JsonObject servers = new JsonObject();
		servers.add(JadxMcpPlugin.PLUGIN_ID, server);

		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return PRETTY_GSON.toJson(root);
	}

	private static ICodeNodeRef resolveNodeRef(JadxDecompiler decompiler, String targetType,
			String className, String memberName, String shortId) {
		JavaClass cls = ToolSupport.requireClass(decompiler, className);
		switch (targetType) {
			case "class":
				return cls.getCodeNodeRef();
			case "method":
				for (JavaMethod mth : cls.getMethods()) {
					if ((!shortId.isEmpty() && shortId.equals(mth.getShortId()))
							|| (shortId.isEmpty() && memberName.equals(mth.getName()))) {
						return mth.getCodeNodeRef();
					}
				}
				throw new IllegalArgumentException("Method not found in " + cls.getFullName());
			case "field":
				for (JavaField field : cls.getFields()) {
					if ((!shortId.isEmpty() && shortId.equals(field.getShortId()))
							|| (shortId.isEmpty() && memberName.equals(field.getName()))) {
						return field.getCodeNodeRef();
					}
				}
				throw new IllegalArgumentException("Field not found in " + cls.getFullName());
			default:
				throw new IllegalArgumentException("Unsupported targetType: " + targetType);
		}
	}

	private static final class GetCurrentContextTool implements McpTool {
		private final McpServerManager manager;

		private GetCurrentContextTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "get_current_context";
		}

		@Override
		public String getDescription() {
			return "Return the currently focused class or member under the caret in jadx-gui.";
		}

		@Override
		public JsonObject getInputSchema() {
			return ToolSupport.schema(new JsonObject());
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxGuiContext gui = manager.getGuiContext();
			JsonObject out = new JsonObject();
			if (gui == null) {
				out.addProperty("guiAvailable", false);
				return out;
			}
			CompletableFuture<ICodeNodeRef> future = new CompletableFuture<>();
			gui.uiRun(() -> {
				try {
					ICodeNodeRef ref = gui.getNodeUnderCaret();
					if (ref == null) {
						ref = gui.getEnclosingNodeUnderCaret();
					}
					future.complete(ref);
				} catch (Exception e) {
					future.completeExceptionally(e);
				}
			});
			try {
				ICodeNodeRef ref = future.get(3, TimeUnit.SECONDS);
				out.addProperty("guiAvailable", true);
				if (ref == null) {
					out.addProperty("hasSelection", false);
				} else {
					out.addProperty("hasSelection", true);
					out.addProperty("nodeType", ref.getAnnType().name());
					out.addProperty("nodeName", String.valueOf(ref));
				}
				return out;
			} catch (Exception e) {
				throw new IllegalStateException("Failed to read GUI context: " + e.getMessage(), e);
			}
		}
	}

	private static final class NavigateToTool implements McpTool {
		private final McpServerManager manager;

		private NavigateToTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "navigate_to";
		}

		@Override
		public String getDescription() {
			return "Open and jump to a class, method, or field inside jadx-gui.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("targetType", ToolSupport.prop("string", "Target kind: class, method, or field (default class)"));
			props.add("className", ToolSupport.prop("string", "Full class name"));
			props.add("memberName", ToolSupport.prop("string", "Method or field name"));
			props.add("shortId", ToolSupport.prop("string", "Optional shortId"));
			return ToolSupport.schema(props, "className");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			JadxGuiContext gui = manager.getGuiContext();
			if (gui == null) {
				throw new IllegalStateException("jadx-gui context is not available in headless mode");
			}
			String targetType = ToolSupport.getString(arguments, "targetType", "class").toLowerCase(Locale.ROOT);
			String className = ToolSupport.getString(arguments, "className", "");
			String memberName = ToolSupport.getString(arguments, "memberName", "");
			String shortId = ToolSupport.getString(arguments, "shortId", "");

			ICodeNodeRef ref = resolveNodeRef(decompiler, targetType, className, memberName, shortId);
			CompletableFuture<Boolean> future = new CompletableFuture<>();
			gui.uiRun(() -> {
				try {
					future.complete(gui.open(ref));
				} catch (Exception e) {
					future.completeExceptionally(e);
				}
			});
			try {
				boolean ok = Boolean.TRUE.equals(future.get(3, TimeUnit.SECONDS));
				JsonObject out = new JsonObject();
				out.addProperty("navigated", ok);
				out.addProperty("target", String.valueOf(ref));
				return out;
			} catch (Exception e) {
				throw new IllegalStateException("Failed to navigate in GUI: " + e.getMessage(), e);
			}
		}
	}

	private static final class RenameNodeTool implements McpTool {
		private final McpServerManager manager;

		private RenameNodeTool(McpServerManager manager) {
			this.manager = manager;
		}

		@Override
		public String getName() {
			return "rename_node";
		}

		@Override
		public String getDescription() {
			return "Rename a class, method, or field by dispatching a NodeRenamedByUser event.";
		}

		@Override
		public JsonObject getInputSchema() {
			JsonObject props = new JsonObject();
			props.add("targetType", ToolSupport.prop("string", "Target kind: class, method, or field"));
			props.add("className", ToolSupport.prop("string", "Full class name"));
			props.add("memberName", ToolSupport.prop("string", "Method or field name"));
			props.add("shortId", ToolSupport.prop("string", "Optional shortId"));
			props.add("newName", ToolSupport.prop("string", "New identifier name"));
			return ToolSupport.schema(props, "targetType", "className", "newName");
		}

		@Override
		public JsonElement execute(JsonObject arguments) {
			JadxDecompiler decompiler = ToolSupport.requireDecompiler(manager);
			String targetType = ToolSupport.getString(arguments, "targetType", "").toLowerCase(Locale.ROOT);
			String className = ToolSupport.getString(arguments, "className", "");
			String memberName = ToolSupport.getString(arguments, "memberName", "");
			String shortId = ToolSupport.getString(arguments, "shortId", "");
			String newName = ToolSupport.getString(arguments, "newName", "").trim();
			if (newName.isEmpty()) {
				throw new IllegalArgumentException("newName must not be blank");
			}

			ICodeNodeRef ref = resolveNodeRef(decompiler, targetType, className, memberName, shortId);
			String oldName = "class".equals(targetType) ? className : memberName;
			decompiler.events().send(new NodeRenamedByUser(ref, oldName, newName));

			JsonObject out = new JsonObject();
			out.addProperty("dispatched", true);
			out.addProperty("targetType", targetType);
			out.addProperty("oldName", oldName);
			out.addProperty("newName", newName);
			return out;
		}
	}
}
