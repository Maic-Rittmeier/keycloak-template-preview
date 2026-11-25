<#-- From the for-keycloak/email-otp-authenticator plugin (theme-resources/templates/html/otp-email.ftl) -->
<#import "template.ftl" as layout>
<@layout.emailLayout>
<p>${kcSanitize(msg("emailOtpYourAccessCode"))?no_esc}</p>
<h1>${otp?no_esc}</h1>
<p>${kcSanitize(msg("emailOtpExpiration", ttlMinutes))?no_esc}</p>
</@layout.emailLayout>
