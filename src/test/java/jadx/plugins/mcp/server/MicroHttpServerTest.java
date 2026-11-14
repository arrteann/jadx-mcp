package jadx.plugins.mcp.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import jadx.plugins.mcp.mcp.McpJsonRpcHandler;
import jadx.plugins.mcp.mcp.McpToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class MicroHttpServerTest {

	@Test
	void handlesPostMcpAndNotificationsOverRealHttp() throws Exception {
		McpJsonRpcHandler rpc = new McpJsonRpcHandler(new McpToolRegistry(), "jadx-mcp", "0.1.0");
		HttpSecurityValidator security = new HttpSecurityValidator("tok-123");

		try (MicroHttpServer server = new MicroHttpServer(0, rpc, security)) {
			server.start();
			URI uri = URI.create("http://127.0.0.1:" + server.getPort() + "/mcp");
			HttpClient client = HttpClient.newHttpClient();

			// Missing token -> 401
			HttpRequest unauthReq = HttpRequest.newBuilder(uri)
					.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
					.build();
			HttpResponse<String> unauthRes = client.send(unauthReq, HttpResponse.BodyHandlers.ofString());
			assertThat(unauthRes.statusCode()).isEqualTo(401);

			// Valid initialize -> 200 OK
			HttpRequest initReq = HttpRequest.newBuilder(uri)
					.header("Authorization", "Bearer tok-123")
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
					.build();
			HttpResponse<String> initRes = client.send(initReq, HttpResponse.BodyHandlers.ofString());
			assertThat(initRes.statusCode()).isEqualTo(200);
			assertThat(initRes.headers().firstValue("Mcp-Session-Id")).isPresent();
			JsonObject json = JsonParser.parseString(initRes.body()).getAsJsonObject();
			assertThat(json.getAsJsonObject("result").get("protocolVersion").getAsString())
					.isEqualTo(McpJsonRpcHandler.PROTOCOL_VERSION);

			// Notification -> 202 Accepted
			HttpRequest notifReq = HttpRequest.newBuilder(uri)
					.header("Authorization", "Bearer tok-123")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
					.build();
			HttpResponse<String> notifRes = client.send(notifReq, HttpResponse.BodyHandlers.ofString());
			assertThat(notifRes.statusCode()).isEqualTo(202);

			// GET /mcp -> 405 Method Not Allowed
			HttpRequest getReq = HttpRequest.newBuilder(uri)
					.header("Authorization", "Bearer tok-123")
					.GET()
					.build();
			HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
			assertThat(getRes.statusCode()).isEqualTo(405);

			// DELETE /mcp -> 204 No Content
			HttpRequest delReq = HttpRequest.newBuilder(uri)
					.header("Authorization", "Bearer tok-123")
					.DELETE()
					.build();
			HttpResponse<String> delRes = client.send(delReq, HttpResponse.BodyHandlers.ofString());
			assertThat(delRes.statusCode()).isEqualTo(204);
		}
	}

	@Test
	void supportsChunkedTransferEncodingOverTcp() throws Exception {
		McpJsonRpcHandler rpc = new McpJsonRpcHandler(new McpToolRegistry(), "jadx-mcp", "0.1.0");
		try (MicroHttpServer server = new MicroHttpServer(0, rpc, new HttpSecurityValidator(null))) {
			server.start();
			String payload = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";
			String hexLen = Integer.toHexString(payload.getBytes(StandardCharsets.UTF_8).length);
			try (Socket sock = new Socket("127.0.0.1", server.getPort())) {
				OutputStream out = sock.getOutputStream();
				String rawReq = "POST /mcp HTTP/1.1\r\n"
						+ "Host: 127.0.0.1:" + server.getPort() + "\r\n"
						+ "Transfer-Encoding: chunked\r\n"
						+ "\r\n"
						+ hexLen + "\r\n"
						+ payload + "\r\n"
						+ "0\r\n\r\n";
				out.write(rawReq.getBytes(StandardCharsets.UTF_8));
				out.flush();

				BufferedReader reader = new BufferedReader(
						new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
				String statusLine = reader.readLine();
				assertThat(statusLine).contains("200 OK");
				String line;
				StringBuilder body = new StringBuilder();
				boolean inBody = false;
				while ((line = reader.readLine()) != null) {
					if (!inBody && line.isEmpty()) {
						inBody = true;
						continue;
					}
					if (inBody) {
						body.append(line);
					}
				}
				JsonObject json = JsonParser.parseString(body.toString()).getAsJsonObject();
				assertThat(json.get("id").getAsInt()).isEqualTo(7);
			}
		}
	}

	@Test
	void rejectsExternalHostHeaderWith403() throws Exception {
		McpJsonRpcHandler rpc = new McpJsonRpcHandler(new McpToolRegistry(), "jadx-mcp", "0.1.0");
		try (MicroHttpServer server = new MicroHttpServer(0, rpc, new HttpSecurityValidator(null))) {
			server.start();
			try (Socket sock = new Socket("127.0.0.1", server.getPort())) {
				OutputStream out = sock.getOutputStream();
				String rawReq = "POST /mcp HTTP/1.1\r\n"
						+ "Host: evil.example:8650\r\n"
						+ "Content-Length: 2\r\n"
						+ "\r\n{}";
				out.write(rawReq.getBytes(StandardCharsets.US_ASCII));
				out.flush();

				BufferedReader reader = new BufferedReader(
						new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
				String statusLine = reader.readLine();
				assertThat(statusLine).contains("403 Forbidden");
			}
		}
	}
}
