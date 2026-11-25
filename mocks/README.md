Custom mock data. Layered on top of the bundled defaults (deep merge):

    login/_common.json            → all login pages
    login/<page>.json             → one page, e.g. login/login-otp.json
    login/<page>.<variant>.json   → additional variant in the menu, e.g. login/login.locked.json
    email/_common.json, email/<template>.json, email/<template>.<variant>.json

A value of `null` removes a default. Comments (// and /* */) are allowed.
The bundled defaults live in src/main/resources/mocks/.
