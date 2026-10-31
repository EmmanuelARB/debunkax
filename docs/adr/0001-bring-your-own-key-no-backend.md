# Bring your own key, no backend of our own

Debunkax has no server. The app calls the user's AI provider directly with the user's own key: Claude (key pasted, stored encrypted in Android Keystore) or OpenRouter (obtained through its OAuth PKCE flow). Every request asks for zero data retention where the provider supports it. We chose this over a shared key bundled in the app (extractable and abusable) and over our own proxy server (we'd pay per Check, and would have to prove the server stores nothing). The user pays for their own Checks and controls where their Claims go.

## Consequences

- Only models whose web search really honours the Trusted Source allowlist are offered: Claude, and on OpenRouter Claude or OpenAI models, or a forced Exa engine. Others would silently drop the allowlist and break the equal-treatment argument.
- Both the Trusted Source list and the tested model list ship inside the app, so changing either requires an app update. Since the app is distributed as a direct APK, it checks a public static release page at launch and offers the new version; this read is not a backend of our own.
- We never see a single Claim or Verdict. Any feedback on wrong Verdicts must go through the user.
- A key's owner can disable web search at the account level; the app must report that failure clearly instead of falling back to unsourced Verdicts.
