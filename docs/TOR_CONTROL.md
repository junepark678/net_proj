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

Authentication uses COOKIE hex authentication. It does not negotiate SAFECOOKIE
or implement password authentication. COOKIE sends the cookie without verifying
the server; use a trusted local endpoint. The process manager owns reading and
protecting the cookie file. No cookie is logged or retained by the client.

Successful authentication subscribes to `STATUS_CLIENT`, `CIRC`, and `HS_DESC`
and queries `status/bootstrap-phase` to initialize clients joining a running
Tor. `setEvents(vararg TorEventType)` replaces these subscriptions; an empty
list disables all events, including bootstrap updates. `bootstrap` is a
`StateFlow<Int>` starting at zero; collectors may conflate fast updates.
`events` is a live, non-replaying `SharedFlow<TorEvent>`. It keeps 64 pending
events for slow collectors and drops the oldest on overflow so logging cannot
block replies. Collect before authentication to observe its initial events.
Unknown statuses, fields and events retain their raw replies for consumers.

Commands are serialized, with an independent event reader. Replies support
space, `-` continuation and `+` data lines, dot unescaping, 250/251 success,
and negative replies. `getInfo` normalizes data blocks to LF without a final
line terminator. `TorCommandException` preserves the status and every reply
line; 514/515 are identifiable through `isAuthenticationFailure`.
Malformed replies, transport failures, EOF, and explicit close fail pending
commands. A negative reply alone leaves the session usable if Tor keeps the
connection open. Tor normally closes it after an authentication failure.

Apply `withTimeout` where needed: no global command timeout is imposed.
Cancelling an in-flight command closes the connection because its eventual
reply cannot safely be matched to a later command. Cancelling while waiting
for the command mutex does not disturb the active command. Cancelling the
owning scope releases the TCP socket even when its reader is idle.
`close()` disconnects the client; only `signal(TorSignal.SHUTDOWN)` requests
process shutdown. Process launch and onion-service commands belong to their
own components.

Protocol references: [message framing](https://spec.torproject.org/control-spec/message-format.html),
[commands](https://spec.torproject.org/control-spec/commands.html), and
[replies and events](https://spec.torproject.org/control-spec/replies.html).

## Verification

No Tor or network access is needed for the transcript tests and loopback TCP
tests:

```sh
ANDROID_HOME=/home/june/Android/Sdk ./gradlew :shared:testAndroidHostTest :shared:desktopTest
```

The desktop smoke test is skipped unless `TOR_CONTROL_SMOKE_BINARY` points at
a Tor executable. It starts an isolated Tor with a temporary data directory,
cookie authentication, an automatic control port and no SOCKS listener. It
checks actual cookie authentication, intermediate bootstrap progress and 100%,
GETINFO (including a 552 response), SETEVENTS, NEWNYM and SHUTDOWN. It waits up
to 180 seconds for bootstrap and requires access to the public Tor network.
Logs remain in the printed temporary directory on failure. It does not
publish an onion service or use the user's running Tor instance.

```sh
ANDROID_HOME=/home/june/Android/Sdk TOR_CONTROL_SMOKE_BINARY=/path/to/tor \
  ./gradlew :shared:desktopTest --tests '*TorControlSmokeTest' --rerun-tasks
```

For a binary distributed with bundled shared libraries, set `LD_LIBRARY_PATH`
to that bundle's library directory as required by the distribution.
