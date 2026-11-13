package jadx.plugins.mcp.tools;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.IJadxEvent;
import jadx.api.plugins.events.JadxEvents;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.fixtures.SampleTarget;
import jadx.plugins.mcp.mcp.McpJsonRpcHandler;

import static org.assertj.core.api.Assertions.assertThat;

class GuiToolsTest {

	@TempDir
	static Path tempDir;

	private static JadxDecompiler decompiler;

	@BeforeAll
	static void setUpClass() throws Exception {
		Path sampleClass = tempDir.resolve("SampleTarget.class");
		try (InputStream in = SampleTarget.class.getResourceAsStream("SampleTarget.class")) {
			assertThat(in).isNotNull();
			Files.copy(in, sampleClass);
		}
		JadxArgs args = new JadxArgs();
		args.setInputFile(sampleClass.toFile());
		args.setOutDir(tempDir.resolve("out").toFile());
		decompiler = new JadxDecompiler(args);
		decompiler.load();
	}

	@AfterAll
	static void tearDownClass() {
		if (decompiler != null) {
			decompiler.close();
		}
	}

	@Test
	void getCurrentContextHandlesHeadlessAndGuiModes() {
		McpServerManager manager = new McpServerManager("0.1.0");
		manager.setDecompiler(decompiler);
		GuiTools.register(manager.getRegistry(), manager);
		McpJsonRpcHandler rpc = new McpJsonRpcHandler(manager.getRegistry(), "jadx-mcp", "0.1.0");

		// Headless: guiContext == null
		JsonObject headless = callTool(rpc, "get_current_context", new JsonObject());
		assertThat(headless.get("guiAvailable").getAsBoolean()).isFalse();

		// With GUI context
		JavaClass cls = decompiler.searchJavaClassByOrigFullName(SampleTarget.class.getName());
		ICodeNodeRef nodeRef = cls.getCodeNodeRef();
		AtomicReference<ICodeNodeRef> openedRef = new AtomicReference<>();

		JadxGuiContext guiStub = (JadxGuiContext) Proxy.newProxyInstance(
				JadxGuiContext.class.getClassLoader(),
				new Class<?>[] { JadxGuiContext.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "uiRun":
							((Runnable) args[0]).run();
							return null;
						case "getEnclosingNodeUnderCaret":
						case "getNodeUnderCaret":
							return nodeRef;
						case "open":
							openedRef.set((ICodeNodeRef) args[0]);
							return true;
						default:
							return null;
					}
				});
		manager.setGuiContext(guiStub);

		JsonObject active = callTool(rpc, "get_current_context", new JsonObject());
		assertThat(active.get("guiAvailable").getAsBoolean()).isTrue();
		assertThat(active.get("nodeName").getAsString()).contains("SampleTarget");

		JsonObject navArgs = new JsonObject();
		navArgs.addProperty("className", SampleTarget.class.getName());
		JsonObject navRes = callTool(rpc, "navigate_to", navArgs);
		assertThat(navRes.get("navigated").getAsBoolean()).isTrue();
		assertThat(openedRef.get()).isEqualTo(nodeRef);
	}

	@Test
	void renameNodeDispatchesNodeRenamedEvent() {
		McpServerManager manager = new McpServerManager("0.1.0");
		manager.setDecompiler(decompiler);
		GuiTools.register(manager.getRegistry(), manager);
		McpJsonRpcHandler rpc = new McpJsonRpcHandler(manager.getRegistry(), "jadx-mcp", "0.1.0");

		List<IJadxEvent> received = new ArrayList<>();
		decompiler.events().addListener(JadxEvents.NODE_RENAMED_BY_USER, received::add);

		JsonObject renameArgs = new JsonObject();
		renameArgs.addProperty("targetType", "method");
		renameArgs.addProperty("className", SampleTarget.class.getName());
		renameArgs.addProperty("memberName", "run");
		renameArgs.addProperty("newName", "executeTask");

		JsonObject res = callTool(rpc, "rename_node", renameArgs);
		assertThat(res.get("dispatched").getAsBoolean()).isTrue();
		assertThat(res.get("newName").getAsString()).isEqualTo("executeTask");
		assertThat(received).hasSize(1);
		assertThat(((NodeRenamedByUser) received.get(0)).getNewName()).isEqualTo("executeTask");
	}

	@Test
	void buildsReadyToPasteAgentJsonConfig() {
		String cfg = GuiTools.buildAgentConfigJson("http://127.0.0.1:8650/mcp", "my-tok");
		JsonObject parsed = JsonParser.parseString(cfg).getAsJsonObject();
		JsonObject server = parsed.getAsJsonObject("mcpServers").getAsJsonObject("jadx-mcp");
		assertThat(server.get("url").getAsString()).isEqualTo("http://127.0.0.1:8650/mcp");
		assertThat(server.getAsJsonObject("headers").get("Authorization").getAsString())
				.isEqualTo("Bearer my-tok");
	}

	private static JsonObject callTool(McpJsonRpcHandler rpc, String name, JsonObject arguments) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", arguments);

		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", 1);
		req.addProperty("method", "tools/call");
		req.add("params", params);

		JsonObject response = JsonParser.parseString(rpc.handle(req.toString())).getAsJsonObject();
		assertThat(response.has("error")).isFalse();
		JsonObject result = response.getAsJsonObject("result");
		assertThat(result.get("isError").getAsBoolean()).isFalse();
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
