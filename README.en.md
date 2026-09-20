# limitchecker

English | [日本語](README.md)

See how much Claude Code and Codex quota you have left, from your Android home
screen and notification area.

It solves one problem: you start a long task without knowing how much quota is
left, and it stops halfway through.

## The point: it holds no account credentials

**This app never logs into your Claude or OpenAI account.** It asks for no API key.

- Claude Code — it uses the values Claude Code already hands to its `statusLine`
- Codex — it calls the App Server's `account/rateLimits/read`, which handles auth itself

Neither path reads `~/.claude/.credentials.json` or `~/.codex/auth.json`.
**The only secret the Android app holds is the token for a hub you run yourself**,
and even that is encrypted with a key held in the Android Keystore.

The cost of this design: **Claude Code's numbers only refresh while Claude Code is
running.** When they go stale, the app greys them out and says so rather than
pretending to be current.

## How it works

```
Your machine (Mac / Linux)            one host          Android
┌──────────────────────┐        ┌─────────┐      ┌──────────────┐
│ Claude Code              │        │          │      │ widget        │
│   └ statusLine ────→ agent │ ─POST─→│   hub    │─GET─→│ notification  │
│ Codex app-server ───→ agent │        │          │      │ status bar    │
└──────────────────────┘        └─────────┘      └──────────────┘
```

| Part | Role | Resident |
| --- | --- | --- |
| agent | Extracts only the quota numbers and sends them to the hub | No |
| hub | Aggregates every machine, folded per account | One host only |
| Android | Draws the widget, notification and status bar icon | — |

Quota windows belong to the account, not the machine, so adding machines does not
add rings.

## What it looks like

The representation changes with the available area.

| Size | Representation |
| --- | --- |
| ≥ 220dp wide and ≥ 170dp tall | **Horizontal bars**, each labelled with its window, remaining and reset time |
| ≥ 190dp wide | Two double donuts side by side |
| Tall and narrow | Two double donuts stacked |
| 1×1 and similar | A single double donut |

- **Remaining is encoded as arc or bar length**, so nothing is lost if you cannot
  read the colours
- **Three palettes** (blue/orange, monochrome, traffic light). The default is
  distinguishable under the common forms of colour vision deficiency
- **Three backgrounds** (opaque, translucent, transparent)
- **Pin it to the notification area** and show it in the status bar
- Four status bar representations; pick Claude, Codex, or both

## Setup

Python 3 is the only dependency. The hub and agent install nothing else.

### 1. Run the hub (on one machine)

A Linux box that is always on is the best host. macOS works the same way.

```sh
git clone https://github.com/mirute02/limitchecker.git
cd limitchecker
./deploy/install-hub.sh
```

The script absorbs the difference between systemd (Linux) and launchd (macOS).
It starts at boot and restarts on failure.

```sh
./deploy/install-hub.sh --status      # check
./deploy/install-hub.sh --uninstall   # remove
```

To reach it from outside your home, set this in `.env` and run the script again:

```
LIMITCHECKER_BIND=tailscale
```

The literal word `tailscale` makes the hub look up this machine's Tailscale
address. **`0.0.0.0` is refused at startup** so a misconfiguration cannot expose
the hub to the internet.

### Using it over Tailscale

**The address the hub binds to and the URL you give the app are not the same thing.**

| | What goes there |
| --- | --- |
| `LIMITCHECKER_BIND` in `.env` | `tailscale` (or an IP). A bind address must exist on this machine |
| **hub URL in the app** | **The MagicDNS name**: `http://gpu.tailnet-name.ts.net:8787` |

The app permits cleartext HTTP only to `localhost` and `*.ts.net`
(`network_security_config`). **An IP address does not match that rule**, so
`http://100.x.y.z:8787` is refused before any request is sent. A short name
(just `gpu`) does not end in `.ts.net`, so it is refused too.

**You do not have to look any of this up.** `./deploy/install-hub.sh` prints the
exact URL to paste into the app when it finishes.

### 2. Install the agent (on every machine running Claude Code or Codex)

```sh
./deploy/install-agent.sh
```

It adds a `statusLine` entry to `settings.json` and, if Codex is present,
registers a periodic fetch. **It does not disturb your existing settings**, and it
stops rather than overwriting a `statusLine` you already have.

```sh
./deploy/install-agent.sh --status
./deploy/install-agent.sh --uninstall
```

Where Codex needs a network workaround (Termux, for example), point `.env` at your
wrapper. A shell `alias` is not expanded inside scripts, so without this the
wrapper is bypassed.

```
LIMITCHECKER_CODEX_BIN=/path/to/codex-wrapper
```

### 3. The Android app

Install the APK from [Releases](https://github.com/mirute02/limitchecker/releases),
or build it yourself.

```sh
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug
```

Requires JDK 17+, the Android SDK (compileSdk 37) and Gradle 9.

### 4. Connect

On the machine hosting the hub:

```sh
python3 hub/pair.py
```

It prints a six-digit code. Enter it in the app together with the hub URL, in the "接続コードで設定"
(pair with a code) section. This saves you transcribing a 43-character token.

The app's interface is in Japanese.

The code is **valid for five minutes, single use, and cut off after five wrong
attempts**.

## Permissions

| Permission | Purpose | Runtime prompt |
| --- | --- | --- |
| `INTERNET` | Talking to the hub | No |
| `ACCESS_NETWORK_STATE` | Skipping work when offline | No |
| `POST_NOTIFICATIONS` | The pinned notification (optional) | Yes |

`WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` are added by
WorkManager. `POST_NOTIFICATIONS` is the only one that prompts.

Location, storage, contacts, `QUERY_ALL_PACKAGES`, accessibility services and
battery-optimisation exemption are **never requested**.

## Battery

| State | Background traffic |
| --- | --- |
| No widget, no notification | None |
| Widget or notification present | Every 15 minutes, **paused while the screen is off** |

Quota does not move minute to minute, so a shorter interval adds requests without
adding information.

## Security

The rules for secrets and personal data are in
[docs/security.md](docs/security.md) (Japanese). Audit records are in
[docs/audit-2026-09-20.md](docs/audit-2026-09-20.md).

In short:

- The hub refuses to start without a token, with a token under 16 characters, or
  bound to `0.0.0.0`
- Tokens are compared with `hmac.compare_digest`
- The agent extracts fields by allowlist, so session IDs, working directories,
  costs and token counts never leave the machine
- Cleartext HTTP is limited to localhost and `*.ts.net` by
  `network_security_config`
- `allowBackup="false"`; the token is encrypted with an Android Keystore key
- A pre-commit hook scans for tokens, absolute paths, e-mail addresses and
  Tailscale addresses

If you contribute, enable that hook:

```sh
git config core.hooksPath .githooks
```

## Limitations

- **Claude Code's numbers only refresh while Claude Code runs.** After ten minutes
  they dim; after an hour they go grey. Codex is polled, so it is unaffected
- **Per-model weekly quota is not available.** `statusLine` returns only the
  five-hour and weekly windows
  ([measurements](docs/findings-statusline.md))
- **There is no "waiting for input" notification.** Claude Code's Remote Control
  already does this (`/config` → "Push when actions required")

## Documentation

Design notes are written in Japanese.

| | |
| --- | --- |
| [docs/design.md](docs/design.md) | The original design document |
| [docs/decisions.md](docs/decisions.md) | Every departure from it, and why |
| [docs/findings-statusline.md](docs/findings-statusline.md) | What `statusLine` actually returns |
| [docs/security.md](docs/security.md) | Security rules |
| [docs/audit-2026-09-20.md](docs/audit-2026-09-20.md) | Audit records |

## Status

| Item | State |
| --- | --- |
| agent and hub | Done |
| Widget | Done |
| Pinned notification and status bar | Done |
| Codex support | Done |
| Pairing code | Done |
| Burn rate and exhaustion forecast | Not started |
| Event detection | Dropped in favour of Remote Control |

## Licence

MIT. See [LICENSE](LICENSE).
