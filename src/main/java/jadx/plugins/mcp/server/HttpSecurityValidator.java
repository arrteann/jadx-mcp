package jadx.plugins.mcp.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Validates HTTP Host, Origin, and optional Bearer token to prevent DNS rebinding
 * and unauthorized local access.
 */
public final class HttpSecurityValidator {

	private final String expectedToken;

	public HttpSecurityValidator(String expectedToken) {
		this.expectedToken = (expectedToken == null || expectedToken.trim().isEmpty())
				? null
				: expectedToken.trim();
	}

	/**
	 * Checks whether the Host header targets loopback only.
	 */
	public boolean isValidHost(String hostHeader) {
		if (hostHeader == null || hostHeader.trim().isEmpty()) {
			return false;
		}
		String host = extractHostWithoutPort(hostHeader.trim().toLowerCase(Locale.ROOT));
		return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
	}

	/**
	 * Checks whether the Origin header is absent (non-browser clients) or points to loopback.
	 */
	public boolean isValidOrigin(String originHeader) {
		if (originHeader == null || originHeader.trim().isEmpty()) {
			return true;
		}
		String origin = originHeader.trim().toLowerCase(Locale.ROOT);
		String remainder;
		if (origin.startsWith("http://")) {
			remainder = origin.substring("http://".length());
		} else if (origin.startsWith("https://")) {
			remainder = origin.substring("https://".length());
		} else {
			return false;
		}
		int slashIdx = remainder.indexOf('/');
		String authority = slashIdx >= 0 ? remainder.substring(0, slashIdx) : remainder;
		return isValidHost(authority);
	}

	/**
	 * Validates the Authorization header when a Bearer token is configured.
	 */
	public boolean isAuthorized(String authHeader) {
		if (expectedToken == null) {
			return true;
		}
		if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
			return false;
		}
		String provided = authHeader.substring("Bearer ".length()).trim();
		return MessageDigest.isEqual(
				expectedToken.getBytes(StandardCharsets.UTF_8),
				provided.getBytes(StandardCharsets.UTF_8));
	}

	private static String extractHostWithoutPort(String authority) {
		if (authority.startsWith("[")) {
			int closeBracket = authority.indexOf(']');
			if (closeBracket < 0) {
				return "";
			}
			String afterBracket = authority.substring(closeBracket + 1);
			if (!afterBracket.isEmpty() && !isValidPortSuffix(afterBracket)) {
				return "";
			}
			return authority.substring(1, closeBracket);
		}
		int colonIdx = authority.indexOf(':');
		if (colonIdx < 0) {
			return authority;
		}
		if (!isValidPortSuffix(authority.substring(colonIdx))) {
			return "";
		}
		return authority.substring(0, colonIdx);
	}

	private static boolean isValidPortSuffix(String suffix) {
		if (!suffix.startsWith(":") || suffix.length() < 2) {
			return false;
		}
		for (int i = 1; i < suffix.length(); i++) {
			if (!Character.isDigit(suffix.charAt(i))) {
				return false;
			}
		}
		return true;
	}
}
