<#-- Shared HTML layout for all emails. The individual emails (html/*.ftl) come from Keycloak. -->
<#macro emailLayout>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<body style="margin:0;padding:0;background:#eef4f1;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#eef4f1;padding:32px 0;">
  <tr><td align="center">
    <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:8px;overflow:hidden;font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1f2d27;">
      <tr><td style="background:#0b6e4f;padding:20px 32px;color:#ffffff;font-size:20px;font-weight:bold;">${realmName}</td></tr>
      <tr><td style="padding:28px 32px;">
        <#nested>
      </td></tr>
      <tr><td style="padding:16px 32px;background:#f6f9f7;color:#6b7c74;font-size:12px;">
        ${msg("emailFooter", realmName)}
      </td></tr>
    </table>
  </td></tr>
</table>
</body>
</html>
</#macro>
