<#ftl output_format="plainText">
<#-- From the for-keycloak/email-otp-authenticator plugin (theme-resources/templates/text/otp-email.ftl) -->
${kcSanitize(msg("emailOtpYourAccessCode"))}

${otp}

${kcSanitize(msg("emailOtpExpiration", ttlMinutes))}
