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
process shutdown. Process launch belongs to the platform process owner;
onion-service commands are provided by `OnionService` below.

Protocol references: [message framing](https://spec.torproject.org/control-spec/message-format.html),
[commands](https://spec.torproject.org/control-spec/commands.html), and
[replies and events](https://spec.torproject.org/control-spec/replies.html).

## Persistent authorized onion service

Use one `OnionService` per identity directory with a dedicated authenticated
control connection. The caller supplies a listening port bound to 127.0.0.1;
Tor maps virtual port 9999 to it. The caller also owns listener/call shutdown.

```kotlin
val identityStore = IdentityStore(directoryStorage())
val publisher = OnionService.open(control, localListenerPort, identityStore, "June")
val contacts = ContactStore.open(
    directoryStorage(),
    isOnline = { publisher.onionAddress.value != null },
    republish = { keys -> publisher.publish(keys.map(::PeerAuthKey)); Unit },
)
// Identity is available offline, for pairing via publisher.identity.pairingPayload().
// Every online transition must read the full list, including changes made offline.
val onion = publisher.publish(contacts.list().map { PeerAuthKey(it.clientAuthPub) })
// Collect control.events BEFORE publish if waiting for HS_DESC UPLOADED.
// ADD_ONION success alone acknowledges creation, not descriptor upload.
publisher.unpublish()
```

On first launch, `open` generates our x25519 pair and sends
`ADD_ONION NEW:ED25519-V3 Flags=V3Auth Port=9999,127.0.0.1:<port>`
with our own `ClientAuthV3` key. It parses `ServiceID` and the tagged `PrivateKey`,
then lets `IdentityStore` persist all three keys and the address in `identity.json`.
It removes that temporary service before returning. This permits offline pairing
after initialization without publishing an unrestricted service. Later opens
only load and validate the saved keys, including x25519 pair correspondence;
missing or corrupt keys are reported without silently rotating the identity.

`publish` sends `ADD_ONION ED25519-V3:<saved blob> Flags=V3Auth`
with one `ClientAuthV3=<base32 public key>` argument per distinct peer.
An empty peer list authorizes only our own key. It never uses `Detach` or
`DiscardPK`. Duplicate publication with the same key set is idempotent;
changing keys sends DEL_ONION then ADD_ONION. Failure after deletion leaves the
publisher offline. `unpublish` sends `DEL_ONION <56-character service id>` and
is idempotent offline. It retains known state on a negative DEL_ONION response.

Cancelling an in-flight service operation closes its dedicated control connection
because a drained ADD_ONION reply could otherwise create an untracked service.
Failed persistence and malformed successful replies also close the connection.
First-run DEL_ONION cleanup has a 10-second bound and closes the connection on
failure. Apply caller timeouts to other operations. Reconnect and reopen after
a disconnect; retry/backoff is not implemented. `onionAddress` reports locally
acknowledged state, and must not be treated as a Tor connection-health monitor.

Before dialing a contact, write **our** private key for that contact's onion:

```kotlin
// Configure Tor's ClientOnionAuthDir to this same private directory.
val auth = ClientOnionAuth(directoryStorage(clientAuthDirectory))
for (peer in contacts.list()) auth.writeForPeer(peer.onion, publisher.identity)
// Load files when starting Tor. If it is already running, reload before dialing:
control.command("SIGNAL HUP")
```

Files are `<56-character onion without .onion>.auth_private`, containing
`<onion>:descriptor:x25519:<our base32 private key>` and a final LF. File names
and contents reuse pairing's v3 checksum and canonical 32-byte base32 validation.
Both these files and `identity.json` use atomic replacement and POSIX 0600 through
`directoryStorage`. Android's directory is its initialized private app data directory;
Keystore wrapping remains a follow-up. Do not export the private identity JSON.

Formats follow the [ADD_ONION control specification](https://spec.torproject.org/control-spec/commands.html#add_onion)
and [Tor client authorization guide](https://community.torproject.org/onion-services/advanced/client-auth/).

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

`OnionServiceIntegrationTest` uses a separate control connection to a trusted
running Tor on loopback port 9051, or `TOR_ONION_TEST_CONTROL_PORT`. It skips if
that port is unreachable or no `TOR_ONION_TEST_COOKIE` file is supplied. It does
not alter Tor's configuration or shut down the Tor process. It creates a temporary
identity and loopback listener, publishes with a peer authorization key, waits up
to 180 seconds for this service's `HS_DESC UPLOADED`, deletes it, and checks key
persistence. Tor bootstrap has its own 180-second bound. The test always closes
its control connection to remove any service left after a failure.

```sh
ANDROID_HOME=/home/june/Android/Sdk \
TOR_ONION_TEST_CONTROL_PORT=9051 \
TOR_ONION_TEST_COOKIE=/path/to/trusted/control_auth_cookie \
  ./gradlew :shared:desktopTest --tests '*OnionServiceIntegrationTest' --rerun-tasks --no-build-cache
```

Ordinary tests cover NEW/reused-key commands, authorization flags, empty/changed
peer lists, cancellation and storage failures, reply validation, RFC 7748 x25519
key derivation, base32 padding bits, and the auth-private file format/permissions:

```sh
ANDROID_HOME=/home/june/Android/Sdk \
  ./gradlew :shared:desktopTest :shared:testAndroidHostTest --rerun-tasks --no-build-cache
```
