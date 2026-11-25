# Keycloak Template Preview

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Shows Keycloak **login pages** and **emails** right in the browser, without a running Keycloak.
Rendered with **FreeMarker**, the same engine Keycloak uses. The original templates, texts and CSS
come from the `keycloak-themes` and `keycloak-themes-vendor` JARs of your Keycloak version (the vendor
JAR carries the PatternFly CSS/JS/fonts the templates reference - without it every page renders
unstyled). Custom themes inherit from them exactly like in real Keycloak (`parent=` / `import=`).
What looks right here looks right on the server too.

No running Keycloak, no Docker container to keep in sync, no clicking through an actual login flow
to see whether a CSS tweak broke the password-reset email - just edit a theme and refresh.

## Why

Building a custom Keycloak theme normally means a slow loop: edit a template, restart or redeploy
Keycloak, click through an actual auth flow to reach the one page you changed, repeat - and emails are
even worse, since you need a real send (or a local SMTP catcher) just to see a rendered HTML email.
This tool renders the exact same FreeMarker templates and theme-resolution chain Keycloak uses, outside
of Keycloak, with realistic mock data standing in for a real realm/user/session. Save a file, see the
result in under a second.

```
┌ Login | Email ┐ Theme [mytheme] Language [de]  Desktop|Tablet|Mobile  Light|Dark   ● Live reload
│ login          │ ┌──────────────────────────────────────────────┐
│ login-otp      │ │        rendered template (iframe)            │
│  default error │ │                                              │
│  multiple-dev… │ │                                              │
│ register       │ └──────────────────────────────────────────────┘
```

## Getting started

Requirement: Java 21 (no local Gradle needed, the wrapper is enough).

```bash
./gradlew shadowJar                                 # builds build/libs/keycloak-template-preview.jar
java -jar build/libs/keycloak-template-preview.jar  # → http://localhost:8090
```

Without the fat JAR, use `./gradlew run`. With Docker: `docker compose up --build`. Without an explicit
`--themes=`, `./themes` is used, plus `./plugin-themes` automatically if that folder exists (so run it
from the repo root - `./gradlew run` does this correctly, an IDE run config needs its working directory
set there too).

**Keycloak version:** it must match your servers, since the base templates change between versions.

```bash
./gradlew shadowJar -Pkeycloak.version=26.4.7
```

### Options

| Option | Default | Meaning |
|---|---|---|
| `--themes=DIR[,DIR]` | `./themes`, plus `./plugin-themes` if it exists | theme folders, comma-separated for more than one; each gets its own dropdown group named after the folder, live-reloaded. Setting this explicitly replaces the default entirely, so include `./plugin-themes` yourself if you still want it |
| `--mocks=DIR` | `./mocks` | custom mock data |
| `--port`, `--host` | `8090`, `127.0.0.1` | |
| `--locale` | `de` | starting language |
| `--keycloak-themes=DIR` | – | base themes from a folder instead of the two JARs, e.g. a real Keycloak server's `themes/` directory (not just the extracted `lib/lib/main/org.keycloak.keycloak-themes-*.jar` - that one alone is missing the vendor CSS too) |
| `--check[=THEME]` | – | renders all templates and variants and exits with code 1 on errors (for CI) |

All options are also available as environment variables: `KC_PREVIEW_THEMES`, `KC_PREVIEW_MOCKS`, `KC_PREVIEW_PORT`, …

## Custom theme

The structure is the same as under `/opt/keycloak/themes`. Only what deviates from the default belongs in the theme.

```
themes/mytheme/
  login/
    theme.properties          parent=keycloak.v2, import=common/keycloak, styles=css/styles.css css/custom.css
    resources/css/custom.css
    resources/img/logo.svg
    messages/messages_de.properties   only the changed texts
    login.ftl                 optional: override a template completely (copy from the keycloak-themes JAR)
  email/
    theme.properties          parent=keycloak
    html/template.ftl         shared HTML layout for all emails
    messages/messages_de.properties
```

`themes/mytheme` is a working example with its own colors, dark mode, texts and HTML email layout.

### Reusable building blocks (e.g. SPI plugin themes)

`import=` isn't limited to `common/keycloak` — any theme folder works as an import target via
`import=<type>/<name>`, merging its templates, resources and messages into the chain without touching
its own `parent=`. That's useful for a custom authenticator SPI that ships its own login page and/or
email, so you can preview it merged into your real theme instead of looking at it in isolation.

Such building blocks aren't real themes of your own, so they live in a second folder, `plugin-themes/`,
picked up automatically alongside `./themes` (see the `--themes` option above). Keeping them out of
`themes/` means `themes/` only ever holds what you'd actually deploy, and `exportDeployTheme` (below)
never needs to know these building blocks exist. Every `--themes` folder beyond the first gets its own
dropdown group, named after the folder (`plugin-themes` → "Plugin themes") - so point `--themes` at as
many folders as you like, each shows up separately.

`plugin-themes/email-otp` is an example: the login page and email for the
[for-keycloak/email-otp-authenticator](https://github.com/for-keycloak/email-otp-authenticator) plugin,
copied from its `theme-resources/`.

```
plugin-themes/email-otp/
  login/
    theme.properties          parent=keycloak.v2, import=common/keycloak (so it also previews standalone)
    login-email-otp.ftl       the plugin's OTP code form
    messages/messages_de.properties
  email/
    theme.properties          parent=keycloak
    html/otp-email.ftl, text/otp-email.ftl
    messages/messages_de.properties
```

`themes/mytheme/login/theme.properties` pulls it in with `import=common/keycloak,login/email-otp`, and
`themes/mytheme/email/theme.properties` with `import=email/email-otp` — so the plugin's page/email shows
up rendered with `mytheme`'s own branding, right next to the built-in pages. Any other custom theme can
pull in the same building block with that one line. Because `email-otp` also has its own `parent=`, it's
additionally selectable as a theme on its own (on plain Keycloak styling) — handy for telling apart a
rendering problem in the plugin's template from one in your own theme. Its `theme.properties` also sets
`buildingBlock=true`, so selected on its own it only lists what it ships itself (just the OTP page),
not the whole inherited keycloak.v2 catalog — set that flag on any theme meant mainly to be imported
elsewhere, not browsed directly.

> **On a real Keycloak server**, a plugin like this usually injects its template/messages into *every*
> theme automatically via Keycloak's generic theme-resources mechanism (the plugin JAR just needs to be
> in `providers/`) — no `import=` needed there at all. The `import=login/email-otp` line only exists so
> *this preview tool*, which has no such generic mechanism, can render the page merged into `mytheme`.
> Use `./gradlew exportDeployTheme` (see "Taking it to real Keycloak" below) to get a copy of `mytheme`
> with that line stripped back out before deploying — otherwise Keycloak will fail to resolve the import,
> since no theme named `email-otp` exists on the real server.
>
> To actually customize the plugin's page/texts for your deployed theme, don't edit `plugin-themes/email-otp`
> — add your own override file or message key directly in `themes/mytheme` instead (e.g.
> `themes/mytheme/login/login-email-otp.ftl`, or just the one key you want to change in
> `themes/mytheme/login/messages/messages_de.properties`). `mytheme`'s own files always win over anything
> pulled in via `import=`, both here and on the real server, so only what you don't override still comes
> from the plugin's defaults.

### Keeping a customer's theme in its own (private) repo

If you build Keycloak themes for clients, their branding usually shouldn't live in this public repo.
`--themes` doesn't care where a folder comes from, so clone their theme repo next to this one and add
it to the list:

```bash
./gradlew run --args="--themes=./themes,../acme-keycloak-theme"
```

`acme-keycloak-theme/<name>` (e.g. `acme-keycloak-theme/acme/login/...`) then shows up in its own
dropdown group, named after that folder, live-reloaded exactly like `./themes`. The client's repo needs
nothing special - it's just theme folders in the same shape as `themes/mytheme` above. The same applies
to `exportThemeJar`, via `-PthemesDir`:

```bash
./gradlew exportThemeJar -Ptheme=acme -PthemesDir=../acme-keycloak-theme
```

In the UI:
- **FTL** (next to Desktop/Tablet/Mobile) shows the raw, unrendered template source instead of the preview -
  resolved through the same theme chain, so it shows exactly which file (own override or inherited) is in effect.
- **Inheritance** at the bottom left shows the lookup chain, e.g. `mytheme/login → keycloak/common → keycloak.v2/login → base/login`.
- **Light/Dark** forces `prefers-color-scheme`, so you can check Keycloak's dark mode independent of the OS setting.
- **Live reload:** saving in `themes/` or `mocks/` reloads the preview.
- **Open in new tab** opens the page standalone, e.g. for DevTools.
- Submitted forms land on a page listing the submitted fields.

## Mock data

Keycloak fills every page with different variables (`realm`, `url`, `auth`, `otpLogin`, `totp`, `profile`, …).
The preview ships realistic defaults for **all** pages from Keycloak 26 (`src/main/resources/mocks/`).
Custom files in `./mocks/` are layered on top (deep merge):

```
mocks/login/_common.json             → all login pages
mocks/login/login-otp.json           → one page
mocks/login/login.locked.json        → additional "locked" variant for login.ftl (shows up as a chip in the menu)
mocks/email/_common.json, mocks/email/password-reset.json, …
```

```jsonc
// mocks/login/login.locked.json
{
  "message": { "type": "warning", "summary": "accountTemporarilyDisabledMessage" },  // message key or text
  "messagesPerField": {
    "username": "invalidUserMessage",
    "password": ["invalidPasswordMinLengthMessage", 12]                             // key with arguments
  },
  "social": null                                                                    // null removes a default
}
```

So the data behaves like Keycloak's Java beans:
- `auth.showUsername` and `auth.showUsername()` both read the key `showUsername`.
- `event.getDetail("x")` reads `detail.x`, `resource.hasDefer()` is true if `defer` is set.
- `msg`, `advancedMsg`, `kcSanitize`, `messagesPerField`, `locale`, `url`, `properties` and `themeResources`
  are generated by the preview itself from the theme and messages.
- Control keys with a leading `_` don't end up in the template: `_supportedLocales` (language picker on the page),
  `_authenticatorConfigured`, and for emails `_subjectKey` and `_subjectArgs`.

If a variable is missing, the preview shows the FreeMarker error with line, template stack and the mock file
it belongs in.

## CI

```bash
java -jar build/libs/keycloak-template-preview.jar --check
#   OK    login mytheme/login.ftl
#   OK    login mytheme/login.ftl [error]
#   …
# 82 ok, 0 failed
```

This way broken templates after a Keycloak upgrade get caught: bump `-Pkeycloak.version` and run `--check`.

## Limitations

- **No real flow:** buttons and links don't lead anywhere; WebAuthn/passkeys don't call a real browser API against a server.
- **`kcSanitize`** here only strips `<script>` and `on…` attributes. Keycloak uses the OWASP sanitizer and may
  strip more. Double-check your own HTML in messages against real Keycloak.
- **Realm translations** (Realm → Localization in the admin UI) don't exist here. Texts only come from the
  `messages_*.properties` files.
- The account and admin console are React apps, not FreeMarker themes; they don't belong in this tool.

## Taking it to real Keycloak

If `mytheme` imports a plugin building block like `plugin-themes/email-otp` (see the note above), don't copy
`themes/mytheme` as-is — that `import=` line is a preview-only crutch that doesn't exist as a real theme
on your server and will break loading of `mytheme` there. Instead:

```bash
./gradlew exportDeployTheme   # → build/deploy/mytheme, with the email-otp import stripped back out
```

Copy or mount the resulting `build/deploy/mytheme` folder to `/opt/keycloak/themes/mytheme`.
(If `mytheme` doesn't import any plugin building block, copying `themes/mytheme` directly works just as well.)

Alternatively, as a JAR under `/opt/keycloak/providers/`, e.g. for a managed Keycloak (Skycloak, …) where
you can't just copy files into `themes/`:

```bash
./gradlew exportThemeJar -Ptheme=mytheme   # → build/theme-jars/mytheme.jar
# theme living in a different repo? Point at it instead of ./themes:
./gradlew exportThemeJar -Ptheme=acme -PthemesDir=../acme-keycloak-theme
```

This packages `<themesDir>/<name>` as `theme/<name>/...` plus a generated `META-INF/keycloak-themes.json`
(same `import=` stripping as `exportDeployTheme`) - equivalent to building this by hand:

```json
{ "themes": [{ "name": "mytheme", "types": ["login", "email"] }] }
```

Then select it in the realm under *Realm settings → Themes*. For a final check with real email delivery,
start Keycloak with `--spi-theme-cache-themes=false --spi-theme-cache-templates=false` and Mailpit as the SMTP server.

## Contributing

Issues and PRs welcome - this is a small, single-file-per-concern codebase (`src/main/java/dev/kcpreview/`,
~1700 lines total), so it's easy to get into. Before sending a PR: `./gradlew check` runs the app's own
`--check` over the bundled themes as part of the build, and `./gradlew exportDeployTheme`/`exportThemeJar`
should still work against `themes/mytheme`. No formal test suite beyond that yet.

## License

[MIT](LICENSE)
