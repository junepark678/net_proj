# Tor control client

`dev.junepark.oniontalkie.tor.TorControl` owns one `ControlTransport` and a
reader coroutine in the supplied scope. Common code has no JDK dependencies;
`JvmControlTransport` lives in `jvmSharedMain`, used by Android and desktop.

Connect to the trusted local control port returned by the platform's Tor
process manager, then pass the **32 cookie bytes** to `authenticate`:

```kotlin
val transport = JvmControlTransport.connect(port = controlPort)
val control = TorControl(transport, scope)
try {
    withTimeout(10_000) { control.authenticate(cookieBytes) }
    val version = control.getInfo("version")
    // Collect control.bootstrap and control.events in the owning scope.
    control.signal(TorSignal.NEWNYM)
} finally {
    control.close()
}
```

Call `authenticate` once on a fresh connection, before issuing other commands.
It sends `PROTOCOLINFO 1` and prefers SAFECOOKIE whenever advertised, even when
COOKIE is also available. SAFECOOKIE sends `AUTHCHALLENGE` with a fresh secure
32-byte client nonce, checks the server's HMAC-SHA256 proof in constant time,
then sends the controller's HMAC-SHA256 proof with `AUTHENTICATE`. Hashes and
server nonces must each contain exactly 32 hexadecimal bytes. The handshake
holds the command mutex so concurrent commands cannot interrupt it.

If only COOKIE is supported, authentication sends the cookie as hex. COOKIE
does not verify the server; use a trusted local endpoint. Unsupported methods,
malformed challenges, and failed server proofs close the connection. A failed
SAFECOOKIE attempt never falls back to COOKIE. Password and unauthenticated
login are unsupported. The process manager owns reading and protecting the
cookie file; the client does not read the server-advertised path. No cookie is
logged or retained by the client.

Successful authentication subscribes to `STATUS_CLIENT`, `CIRC`, and `HS_DESC`
and queries `status/bootstrap-phase` to initialize clients joining a running
Tor. `setEvents(vararg TorEventType)` replaces these subscriptions; an empty
list disables all events, including bootstrap updates. `bootstrap` is a
`StateFlow<Int>` starting at zero; collectors may conflate fast updates.
`events` is a live, non-replaying `SharedFlow<TorEvent>`. It keeps 64 pending
events for slow collectors and drops the oldest on overflow so logging cannot
block replies. Collect before authentication to observe its initial events.
Unknown statuses, fields and events retain their raw replies for consumers.

`awaitTermination()` suspends until the reader observes EOF/failure or the
control client is closed, then returns the terminal cause. Lifecycle owners
should observe this alongside the Tor process log flow; the process flow closes
when Tor exits. `collectBootstrapUntilTorTerminates` combines both signals with
bootstrap collection and fails when either Tor resource ends unexpectedly.

Commands are serialized, with an independent event reader. Replies support
space, `-` continuation and `+` data lines, dot unescaping, 250/251 success,
and negative replies. `getInfo` normalizes data blocks to LF without a final
line terminator. `TorCommandException` preserves the status and every reply
line; 514/515 are identifiable through `isAuthenticationFailure`.

`suspend fun command(line: String): TorReply` is the extension point for higher
level components such as the onion-service publisher in #7. Pass one command
without its line terminator. Empty or whitespace-only strings and any embedded
CR or LF throw `IllegalArgumentException` before writing or closing the
connection. The transport appends CRLF. Callers own command-specific argument
validation and quoting; the client does not log command text or replies.

The returned `TorReply.code` preserves the 2xx status (including 251), and
`TorReply.lines` preserves every continuation line and the final status line.
Each `TorReplyLine` contains `text` and an optional dot-unescaped `data` block.
For a non-2xx reply, `TorCommandException.reply` preserves the same complete
structure. For example, a publisher can issue a formatted `ADD_ONION` command
and extract `ServiceID=` and `PrivateKey=` lines without changing this client:

```kotlin
val reply = control.command(formattedAddOnionCommand)
val serviceId = reply.lines.first { it.text.startsWith("ServiceID=") }
    .text.substringAfter('=')
```

Malformed replies, transport failures, EOF, and explicit close fail pending
commands. A negative reply alone leaves the session usable if Tor keeps the
connection open. Tor normally closes it after an authentication failure.

Apply `withTimeout` where needed: no global command timeout is imposed.
Cancelling or timing out a caller leaves the connection and event reader alive.
An in-flight exchange continues in the client's scope and drains its complete
reply before admitting the next command. If Tor never replies, later commands
keep waiting until the client is closed or its owning scope is cancelled.
Cancelling while waiting for the command mutex does not send that command or
disturb the active command. Cancelling the owning scope releases the TCP socket
even when its reader is idle.
`close()` disconnects the client; only `signal(TorSignal.SHUTDOWN)` requests
process shutdown. Process launch and onion-service commands belong to their
own components.

Protocol references: [message framing](https://spec.torproject.org/control-spec/message-format.html),
[commands](https://spec.torproject.org/control-spec/commands.html), and
[replies and events](https://spec.torproject.org/control-spec/replies.html).

## Onion service integration test

`OnionServiceTorTest` (desktop) publishes with client authorization, waits for the
`HS_DESC UPLOADED` event, and issues `DEL_ONION`. It is skipped unless
`TOR_CONTROL_SMOKE_BINARY` (isolated Tor, needs the public network) or
`TOR_CONTROL_PORT` plus `TOR_CONTROL_COOKIE_FILE` (a running Tor you own) is set:

```sh
ANDROID_HOME="$ANDROID_HOME" TOR_CONTROL_SMOKE_BINARY=/path/to/tor \
  ./gradlew :shared:desktopTest --tests '*OnionServiceTorTest' --rerun-tasks
```

## Verification

No Tor or network access is needed for the transcript tests, SHA-256 and
RFC 4231 HMAC-SHA256 known-answer vectors, SAFECOOKIE handshake vectors, and
loopback TCP tests (including the raw command API and multi-line replies):

```sh
ANDROID_HOME="$ANDROID_HOME" ./gradlew --no-daemon :shared:testAndroidHostTest :shared:desktopTest
```

The desktop smoke test is skipped unless `TOR_CONTROL_SMOKE_BINARY` points at
a Tor executable. It starts an isolated Tor with a temporary data directory,
cookie authentication, an automatic control port and no SOCKS listener. It
checks negotiated cookie authentication (SAFECOOKIE on current Tor), intermediate
bootstrap progress and 100%, GETINFO (including a 552 response), SETEVENTS,
NEWNYM and SHUTDOWN. It waits up
to 180 seconds for bootstrap and requires access to the public Tor network.
Logs remain in the printed temporary directory on failure. It does not
publish an onion service or use the user's running Tor instance.

```sh
ANDROID_HOME="$ANDROID_HOME" TOR_CONTROL_SMOKE_BINARY=/path/to/tor \
  ./gradlew :shared:desktopTest --tests '*TorControlSmokeTest' --rerun-tasks
```

For a binary distributed with bundled shared libraries, set `LD_LIBRARY_PATH`
to that bundle's library directory as required by the distribution.
