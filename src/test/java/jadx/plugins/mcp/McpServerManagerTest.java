package jadx.plugins.mcp;

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerManagerTest {

	@Test
	void fallsBackToNextFreePortWhenPreferredPortIsBusy() throws Exception {
		try (ServerSocket occupied = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
			int busyPort = occupied.getLocalPort();
			McpServerManager manager = new McpServerManager("0.1.0");
			try {
				manager.start(busyPort, true, "");
				assertThat(manager.isRunning()).isTrue();
				assertThat(manager.getActivePort()).isGreaterThan(busyPort);
				assertThat(manager.getActiveUrl()).isEqualTo("http://127.0.0.1:" + manager.getActivePort() + "/mcp");
			} finally {
				manager.stop();
			}
			assertThat(manager.isRunning()).isFalse();
			assertThat(manager.getActivePort()).isEqualTo(-1);
		}
	}

	@Test
	void throwsWhenPreferredPortIsBusyAndFallbackDisabled() throws Exception {
		try (ServerSocket occupied = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
			int busyPort = occupied.getLocalPort();
			McpServerManager manager = new McpServerManager("0.1.0");
			assertThatThrownBy(() -> manager.start(busyPort, false, ""))
					.isInstanceOf(BindException.class);
		}
	}
}
