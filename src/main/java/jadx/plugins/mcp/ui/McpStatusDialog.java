package jadx.plugins.mcp.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import jadx.api.plugins.gui.JadxGuiContext;
import jadx.plugins.mcp.McpOptions;
import jadx.plugins.mcp.McpServerManager;
import jadx.plugins.mcp.tools.GuiTools.ClientFormat;

/**
 * Lightweight Swing dialog displaying live MCP server status, request metrics, and client config.
 */
public final class McpStatusDialog extends JDialog {

	private static final int REFRESH_INTERVAL_MS = 2000;

	private final JadxGuiContext guiContext;
	private final McpServerManager manager;
	private final McpOptions options;

	private final JLabel statusValue = new JLabel();
	private final JLabel endpointValue = new JLabel();
	private final JLabel authValue = new JLabel();
	private final JLabel toolsValue = new JLabel();
	private final JLabel requestsValue = new JLabel();
	private final JLabel uptimeValue = new JLabel();
	private final JComboBox<ClientFormat> formatSelector = new JComboBox<>(ClientFormat.values());
	private final JTextArea configArea = new JTextArea(8, 44);
	private final JButton toggleButton = new JButton();
	private final JButton testButton = new JButton("Test Connection");
	private final JButton copyButton = new JButton("Copy Config");
	private final Timer refreshTimer;

	private McpStatusDialog(JFrame owner, JadxGuiContext guiContext, McpServerManager manager, McpOptions options) {
		super(owner, "JADX MCP Server", false);
		this.guiContext = guiContext;
		this.manager = manager;
		this.options = options;

		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setLayout(new BorderLayout(10, 10));

		JPanel content = new JPanel(new BorderLayout(10, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(buildStatusPanel(), BorderLayout.NORTH);
		content.add(buildConfigPanel(), BorderLayout.CENTER);
		content.add(buildButtonsPanel(), BorderLayout.SOUTH);
		add(content, BorderLayout.CENTER);

		refreshState();
		this.refreshTimer = new Timer(REFRESH_INTERVAL_MS, e -> refreshState());
		this.refreshTimer.start();

		pack();
		setMinimumSize(getSize());
		setLocationRelativeTo(owner);
	}

	public static void show(JadxGuiContext guiContext, McpServerManager manager, McpOptions options) {
		if (guiContext == null) {
			return;
		}
		guiContext.uiRun(() -> {
			JFrame frame = guiContext.getMainFrame();
			McpStatusDialog dialog = new McpStatusDialog(frame, guiContext, manager, options);
			dialog.setVisible(true);
		});
	}

	@Override
	public void dispose() {
		if (refreshTimer != null) {
			refreshTimer.stop();
		}
		super.dispose();
	}

	private JPanel buildStatusPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createTitledBorder("Server Status"));

		GridBagConstraints labelGbc = new GridBagConstraints();
		labelGbc.anchor = GridBagConstraints.WEST;
		labelGbc.insets = new Insets(4, 6, 4, 12);
		labelGbc.gridx = 0;

		GridBagConstraints valueGbc = new GridBagConstraints();
		valueGbc.anchor = GridBagConstraints.WEST;
		valueGbc.fill = GridBagConstraints.HORIZONTAL;
		valueGbc.weightx = 1.0;
		valueGbc.insets = new Insets(4, 0, 4, 6);
		valueGbc.gridx = 1;

		addRow(panel, labelGbc, valueGbc, 0, "Status:", statusValue);
		addRow(panel, labelGbc, valueGbc, 1, "Endpoint:", endpointValue);
		addRow(panel, labelGbc, valueGbc, 2, "Authentication:", authValue);
		addRow(panel, labelGbc, valueGbc, 3, "MCP Tools:", toolsValue);
		addRow(panel, labelGbc, valueGbc, 4, "Requests:", requestsValue);
		addRow(panel, labelGbc, valueGbc, 5, "Uptime:", uptimeValue);
		return panel;
	}

	private static void addRow(JPanel panel, GridBagConstraints labelGbc, GridBagConstraints valueGbc,
			int row, String label, JLabel value) {
		labelGbc.gridy = row;
		valueGbc.gridy = row;
		JLabel title = new JLabel(label);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		panel.add(title, labelGbc);
		panel.add(value, valueGbc);
	}

	private JPanel buildConfigPanel() {
		JPanel panel = new JPanel(new BorderLayout(6, 6));
		panel.setBorder(BorderFactory.createTitledBorder("Client Configuration"));

		JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		topBar.add(new JLabel("Client Format:"));
		formatSelector.addActionListener(e -> refreshState());
		topBar.add(formatSelector);

		configArea.setEditable(false);
		configArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		panel.add(topBar, BorderLayout.NORTH);
		panel.add(new JScrollPane(configArea), BorderLayout.CENTER);
		return panel;
	}

	private JPanel buildButtonsPanel() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

		copyButton.addActionListener(e -> {
			guiContext.copyToClipboard(configArea.getText());
			copyButton.setText("Copied!");
			Timer reset = new Timer(1500, evt -> copyButton.setText("Copy Config"));
			reset.setRepeats(false);
			reset.start();
		});

		testButton.addActionListener(e -> runConnectionTest());
		toggleButton.addActionListener(e -> toggleServer());

		JButton refreshButton = new JButton("Refresh");
		refreshButton.addActionListener(e -> refreshState());

		JButton closeButton = new JButton("Close");
		closeButton.addActionListener(e -> dispose());

		panel.add(toggleButton);
		panel.add(testButton);
		panel.add(copyButton);
		panel.add(refreshButton);
		panel.add(closeButton);
		return panel;
	}

	private void runConnectionTest() {
		String activeUrl = manager.getActiveUrl();
		if (activeUrl == null) {
			statusValue.setText("Stopped (start server first)");
			return;
		}
		testButton.setEnabled(false);
		testButton.setText("Testing...");
		String token = options.getToken();
		Thread worker = new Thread(() -> {
			String resultText = pingEndpoint(activeUrl, token);
			SwingUtilities.invokeLater(() -> {
				testButton.setEnabled(true);
				testButton.setText("Test Connection");
				statusValue.setText(resultText);
				refreshStateCountOnly();
			});
		}, "jadx-mcp-ping-test");
		worker.setDaemon(true);
		worker.start();
	}

	private static String pingEndpoint(String endpointUrl, String token) {
		long startNs = System.nanoTime();
		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(endpointUrl).openConnection();
			conn.setConnectTimeout(3000);
			conn.setReadTimeout(3000);
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/json");
			if (token != null && !token.trim().isEmpty()) {
				conn.setRequestProperty("Authorization", "Bearer " + token.trim());
			}
			byte[] payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
			conn.getOutputStream().write(payload);
			int code = conn.getResponseCode();
			conn.disconnect();
			long ms = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
			return code == 200 ? "Ping OK (" + ms + " ms)" : "Ping HTTP " + code;
		} catch (Exception e) {
			return "Ping failed: " + e.getMessage();
		}
	}

	private void refreshStateCountOnly() {
		requestsValue.setText(Long.toString(manager.getRequestCount()));
	}

	private void toggleServer() {
		if (manager.isRunning()) {
			manager.setLastError(null);
			manager.stop();
		} else {
			try {
				manager.start(options.getPort(), options.isAutoPortFallback(), options.getToken());
			} catch (IOException e) {
				manager.setLastError(e.getMessage());
			}
		}
		refreshState();
	}

	private void refreshState() {
		ClientFormat selectedFormat = (ClientFormat) formatSelector.getSelectedItem();
		McpStatusSnapshot snapshot = McpStatusSnapshot.capture(manager, options, selectedFormat);
		statusValue.setText(snapshot.getStatusText());
		endpointValue.setText(snapshot.getEndpointUrl());
		authValue.setText(snapshot.getAuthText());
		toolsValue.setText(snapshot.getToolCount() + " registered");
		requestsValue.setText(Long.toString(snapshot.getRequestCount()));
		uptimeValue.setText(snapshot.getUptimeText());
		if (!snapshot.getClientConfigJson().equals(configArea.getText())) {
			configArea.setText(snapshot.getClientConfigJson());
			configArea.setCaretPosition(0);
		}
		toggleButton.setText(snapshot.isRunning() ? "Stop Server" : "Start Server");
	}
}
