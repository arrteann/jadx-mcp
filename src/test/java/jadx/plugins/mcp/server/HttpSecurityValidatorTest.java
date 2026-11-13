package jadx.plugins.mcp.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSecurityValidatorTest {

	@Test
	void acceptsLoopbackHostsWithAndWithoutPorts() {
		HttpSecurityValidator validator = new HttpSecurityValidator(null);
		assertThat(validator.isValidHost("127.0.0.1")).isTrue();
		assertThat(validator.isValidHost("127.0.0.1:8650")).isTrue();
		assertThat(validator.isValidHost("localhost")).isTrue();
		assertThat(validator.isValidHost("localhost:8650")).isTrue();
		assertThat(validator.isValidHost("[::1]")).isTrue();
		assertThat(validator.isValidHost("[::1]:8650")).isTrue();
	}

	@Test
	void rejectsExternalOrMalformedHostsToPreventDnsRebinding() {
		HttpSecurityValidator validator = new HttpSecurityValidator(null);
		assertThat(validator.isValidHost(null)).isFalse();
		assertThat(validator.isValidHost("")).isFalse();
		assertThat(validator.isValidHost("evil.com")).isFalse();
		assertThat(validator.isValidHost("localhost.evil.com:8650")).isFalse();
		assertThat(validator.isValidHost("127.0.0.1:abc")).isFalse();
		assertThat(validator.isValidHost("[::1:8650")).isFalse();
	}

	@Test
	void validatesOriginHeaderCorrectly() {
		HttpSecurityValidator validator = new HttpSecurityValidator(null);
		assertThat(validator.isValidOrigin(null)).isTrue();
		assertThat(validator.isValidOrigin("")).isTrue();
		assertThat(validator.isValidOrigin("http://localhost:3000")).isTrue();
		assertThat(validator.isValidOrigin("http://127.0.0.1:8650/app")).isTrue();
		assertThat(validator.isValidOrigin("https://evil.example")).isFalse();
		assertThat(validator.isValidOrigin("file://local")).isFalse();
	}

	@Test
	void enforcesBearerTokenOnlyWhenConfigured() {
		HttpSecurityValidator openValidator = new HttpSecurityValidator("  ");
		assertThat(openValidator.isAuthorized(null)).isTrue();

		HttpSecurityValidator tokenValidator = new HttpSecurityValidator("secret-123");
		assertThat(tokenValidator.isAuthorized(null)).isFalse();
		assertThat(tokenValidator.isAuthorized("Basic dXNlcjpwYXNz")).isFalse();
		assertThat(tokenValidator.isAuthorized("Bearer wrong")).isFalse();
		assertThat(tokenValidator.isAuthorized("Bearer secret-123")).isTrue();
		assertThat(tokenValidator.isAuthorized("bearer secret-123")).isTrue();
	}
}
