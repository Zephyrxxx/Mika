# Manual native-call fallback

Scope: [issue #623](https://github.com/wangzan101/Mika/issues/623), reported on vivo X200 Pro / OriginOS 6 / Android 16. This is a device-validation candidate, not evidence that other OEMs behave identically.

The normal companion path remains first. Its existing verified-audio, allowlist and standby safeguards still govern automatic answering followed by AI audio. No default-dialer role is requested.

The existing device/setup section offers a session-only manual action only after a successful management-permission command, a recognized read-back mode, an explicit negative framework permission check, and successful restoration. Failed commands, unknown checks and incomplete restoration do not qualify. No persistent compatibility switch is offered. Restart, cancellation, helper replacement, stopping AI Phone or restoration of the official path invalidates manual consent.

On the manual path, the user keeps the system Phone UI. Incoming answering requires a tap and the optional Android answering permission; alternatively the user answers in Phone then selects AI control. Outgoing calls may originate in Mika but require explicit takeover. No call record is created just because a native call is visible. Hidden native call screens are not monitored for new manual calls. The companion-ready flag stays false.

## Regression checklist

- Official-capable device: normal preparation, allowlisted verified incoming automatic answer and AI audio without an overlay tap; nonallowlisted callers remain excluded.
- Refusal: action only after a verified refusal; missing phone permission, disconnected helper, command error, unreadable permission state and failed restoration never offer it.
- Session: no automatic fallback after app restart; repeated taps, cancellation and helper replacement revoke control safely.
- vivo: manual incoming answer/takeover, Mika outgoing/manual takeover, handback, call switch/end, stale answer ticket, permission denial and helper disconnect.
- Leaving the native call screen cancels takeover. If the same native mute control is no longer visible, restoration cannot be safely guaranteed; the user is told to unmute in Phone. Do not report automatic handback as successful without real-device verification.

Pure policy tests cover refusal classification and native call identity/ticket lifetime. Settings tests cover nonpersistent session consent, revocation and unchanged official automatic policy. Android compilation, complete project CI and the device checklist remain separate validation stages.
