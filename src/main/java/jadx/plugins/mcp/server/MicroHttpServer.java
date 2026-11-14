package jadx.plugins.mcp.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.plugins.mcp.mcp.McpJsonRpcHandler;

/**
 * Ultra-lightweight HTTP/1.1 server built purely on {@link ServerSocket} for Streamable HTTP MCP.
 * Avoids {@code jdk.httpserver} because it is stripped from official JADX bundled JREs.
 */
public final class MicroHttpServer implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(MicroHttpServer.class);
	private static final int MAX_HEADER_BYTES = 16 * 1024;
	private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
	private static final int SOCKET_TIMEOUT_MS = 30_000;

	private final McpJsonRpcHandler rpcHandler;
	private final HttpSecurityValidator security;
	private final ServerSocket serverSocket;
	private final ExecutorService executor;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong requestCount = new AtomicLong(0);
	private Thread acceptThread;

	public MicroHttpServer(int port, McpJsonRpcHandler rpcHandler, HttpSecurityValidator security) throws IOException {
		this.rpcHandler = rpcHandler;
		this.security = security;
		this.serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
		this.executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "jadx-mcp-worker");
			t.setDaemon(true);
			return t;
		});
	}

	public int getPort() {
		return serverSocket.getLocalPort();
	}

	public long getRequestCount() {
		return requestCount.get();
	}

	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		acceptThread = new Thread(this::acceptLoop, "jadx-mcp-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
	}

	private void acceptLoop() {
		while (running.get() && !serverSocket.isClosed()) {
			try {
				Socket client = serverSocket.accept();
				executor.execute(() -> handleClient(client));
			} catch (IOException e) {
				if (running.get()) {
					LOG.warn("MCP accept error: {}", e.getMessage());
				}
			}
		}
	}

	private void handleClient(Socket socket) {
		try (Socket s = socket) {
			s.setSoTimeout(SOCKET_TIMEOUT_MS);
			BufferedInputStream in = new BufferedInputStream(s.getInputStream());
			OutputStream out = s.getOutputStream();

			String requestLine = readLine(in);
			if (requestLine == null || requestLine.isEmpty()) {
				return;
			}
			requestCount.incrementAndGet();
			String[] parts = requestLine.split(" ");
			if (parts.length < 2) {
				writeResponse(out, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request", null);
				return;
			}
			String method = parts[0].toUpperCase(Locale.ROOT);
			String path = stripQuery(parts[1]);

			Map<String, String> headers = readHeaders(in);
			if (!security.isValidHost(headers.get("host")) || !security.isValidOrigin(headers.get("origin"))) {
				writeResponse(out, 403, "Forbidden", "text/plain; charset=utf-8", "Forbidden host or origin", null);
				return;
			}
			if (!security.isAuthorized(headers.get("authorization"))) {
				writeResponse(out, 401, "Unauthorized", "text/plain; charset=utf-8", "Unauthorized", null);
				return;
			}
			if (!"/mcp".equals(path)) {
				writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found", null);
				return;
			}
			if ("GET".equals(method)) {
				writeResponse(out, 405, "Method Not Allowed", "text/plain; charset=utf-8",
						"Use POST /mcp", Map.of("Allow", "POST, DELETE"));
				return;
			}
			if ("DELETE".equals(method)) {
				writeResponse(out, 204, "No Content", "text/plain; charset=utf-8", "", null);
				return;
			}
			if (!"POST".equals(method)) {
				writeResponse(out, 405, "Method Not Allowed", "text/plain; charset=utf-8",
						"Method Not Allowed", Map.of("Allow", "POST, DELETE"));
				return;
			}

			byte[] bodyBytes = readRequestBody(in, headers);
			if (bodyBytes == null) {
				writeResponse(out, 413, "Payload Too Large", "text/plain; charset=utf-8", "Invalid or oversized body", null);
				return;
			}
			String body = new String(bodyBytes, StandardCharsets.UTF_8);

			String rpcResponse = rpcHandler.handle(body);
			if (rpcResponse == null) {
				writeResponse(out, 202, "Accepted", "text/plain; charset=utf-8", "", null);
			} else {
				writeResponse(out, 200, "OK", "application/json; charset=utf-8", rpcResponse, null);
			}
		} catch (Exception e) {
			if (running.get()) {
				LOG.debug("MCP client handling error: {}", e.getMessage());
			}
		}
	}

	private static String stripQuery(String target) {
		int q = target.indexOf('?');
		return q >= 0 ? target.substring(0, q) : target;
	}

	private static Map<String, String> readHeaders(InputStream in) throws IOException {
		Map<String, String> headers = new HashMap<>();
		int totalBytes = 0;
		while (true) {
			String line = readLine(in);
			if (line == null || line.isEmpty()) {
				break;
			}
			totalBytes += line.length();
			if (totalBytes > MAX_HEADER_BYTES) {
				throw new IOException("Headers too large");
			}
			int colon = line.indexOf(':');
			if (colon > 0) {
				String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
				String value = line.substring(colon + 1).trim();
				headers.put(key, value);
			}
		}
		return headers;
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') {
				break;
			}
			if (b != '\r') {
				buf.write(b);
				if (buf.size() > MAX_HEADER_BYTES) {
					throw new IOException("Line too long");
				}
			}
		}
		if (b == -1 && buf.size() == 0) {
			return null;
		}
		return buf.toString(StandardCharsets.US_ASCII);
	}

	private static byte[] readRequestBody(InputStream in, Map<String, String> headers) throws IOException {
		String transferEncoding = headers.get("transfer-encoding");
		if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
			return readChunkedBody(in);
		}
		int contentLength = parseContentLength(headers.get("content-length"));
		if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
			return null;
		}
		return readFixedBytes(in, contentLength);
	}

	private static byte[] readChunkedBody(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (true) {
			String sizeLine = readLine(in);
			if (sizeLine == null) {
				throw new IOException("Unexpected EOF in chunked body");
			}
			int semi = sizeLine.indexOf(';');
			String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
			int chunkSize;
			try {
				chunkSize = Integer.parseInt(hex, 16);
			} catch (NumberFormatException e) {
				return null;
			}
			if (chunkSize < 0 || out.size() + chunkSize > MAX_BODY_BYTES) {
				return null;
			}
			if (chunkSize == 0) {
				// Consume trailing headers until empty line
				while (true) {
					String trailer = readLine(in);
					if (trailer == null || trailer.isEmpty()) {
						break;
					}
				}
				return out.toByteArray();
			}
			byte[] chunk = readFixedBytes(in, chunkSize);
			out.write(chunk);
			// Consume CRLF after chunk data
			String crlf = readLine(in);
			if (crlf == null) {
				throw new IOException("Unexpected EOF after chunk");
			}
		}
	}

	private static int parseContentLength(String header) {
		if (header == null || header.isEmpty()) {
			return 0;
		}
		try {
			return Integer.parseInt(header.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static byte[] readFixedBytes(InputStream in, int length) throws IOException {
		byte[] buf = new byte[length];
		int read = 0;
		while (read < length) {
			int n = in.read(buf, read, length - read);
			if (n < 0) {
				throw new IOException("Unexpected EOF while reading body");
			}
			read += n;
		}
		return buf;
	}

	private static void writeResponse(OutputStream out, int status, String reason, String contentType,
			String body, Map<String, String> extraHeaders) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		StringBuilder header = new StringBuilder();
		header.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
		header.append("Content-Type: ").append(contentType).append("\r\n");
		header.append("Content-Length: ").append(bytes.length).append("\r\n");
		header.append("Connection: close\r\n");
		if (extraHeaders != null) {
			for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
				header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
			}
		}
		header.append("\r\n");
		out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
		out.write(bytes);
		out.flush();
	}

	@Override
	public void close() {
		if (!running.compareAndSet(true, false) && serverSocket.isClosed()) {
			return;
		}
		try {
			serverSocket.close();
		} catch (IOException e) {
			LOG.debug("Error closing MCP server socket: {}", e.getMessage());
		}
		executor.shutdownNow();
	}
}
