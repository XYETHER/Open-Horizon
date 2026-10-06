# Horizon agent folder boundary

The shipped app gives agent file tools one app-owned workspace folder. Settings shows the actual path. The model cannot pick a different project directory. Saved legacy SAF/shell/SSH projects and Full access do not widen that boundary.

- Canonical paths must remain inside the root; traversal and outside absolute paths fail.
- Symbolic links are rejected; a previously resolved node is checked again before use.
- Child creation/rename accepts only a single name. Recursive delete does not follow links.
- Local Coding offers file creation and HTML navigation/DOM/console inspection. Shell, device, package, logcat, subagent and MCP tools are unavailable.
- Complete tool batches must pass protocol/schema validation. An execution-time allowlist also rejects unadvertised tools.
- HTML preview uses an intercepted workspace HTTPS origin. Only relative HTML targets are accepted; WebView file/content access and external local-page requests are blocked.
- APK does not request MANAGE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE or Shizuku access. Native model storage is private and separate; only the app's trusted model manager accesses it.

Web search and fetch still access public internet sources through HTTP tools. They cannot read arbitrary device files. User-selected images are explicit inputs copied/imported by the app, not a general device-file capability.

This is an application-enforced capability boundary, backed by Android app permissions; it is not a separate inference VM or process sandbox. Changes must retain the boundary tests. Do not add shell/device tools without redesigning their isolation and obtaining a new user requirement.

Validation: AgentFolderBoundaryTest, bounded legacy UnboundedFileFsTest, LocalBrowserBoundaryTest, adversarial LocalAgentEngineTest, and real Android AgentFolderDeviceTest. Never clear/uninstall the main app to run them.

Public research HTTP now rejects non-public DNS results and non-public connection addresses, including redirects. This prevents web tools from reaching phone-local and LAN services; it does not turn public website access into an offline mode.

## Verified public-web pre-connection boundary (0.7.2)
An actual Android regression found that numeric IP URLs bypass OkHttp's custom DNS. The fixed client preflights host addresses, including literals, before connecting. Built-in redirects are disabled; a bounded manual loop validates each destination and strips cross-origin credentials. Actual phone localhost blocking passed, alongside JVM zero-request localhost and public-to-private redirect regressions. Application tool containment is not a separate OS process or VM.

## Background HTML WebSocket regression (0.7.2)
An actual headless WebView probe made two loopback TCP connections on the baseline build. The final build sets blockNetworkLoads for local navigation and supplies the same restrictive response CSP to background and visible previews, including connect-src none and worker-src none. Inline scripts and relative assets remain usable. Final on-phone probe executed its script, recorded connect-src, and made zero connections. Seven boundary/native/HTML phone tests passed in 2.371 seconds. Public-web HTTP tools separately validate addresses and redirects. This is an application tool boundary, not a separate OS VM. Test-only trusted observation uses direct evaluateJavascript; no unsafe-eval or arbitrary JavaScript tool is exposed to the agent.
