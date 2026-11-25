"""Verify SMTP authentication without sending a message or printing credentials."""
from pathlib import Path
import smtplib
import ssl

settings = {}
for line in Path('/mnt/nvme/labflow/shared/app.env').read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        settings[key] = value
try:
    with smtplib.SMTP(settings['MAIL_HOST'], int(settings.get('MAIL_PORT', '587')), timeout=15) as smtp:
        smtp.ehlo()
        if settings.get('MAIL_STARTTLS', 'true').lower() == 'true':
            smtp.starttls(context=ssl.create_default_context())
            smtp.ehlo()
        if settings.get('MAIL_SMTP_AUTH', 'true').lower() == 'true':
            smtp.login(settings['MAIL_USERNAME'], settings['MAIL_PASSWORD'])
    print('PASS: SMTP connection, STARTTLS and authentication; no message sent')
except Exception as error:
    print('FAIL: SMTP verification (' + type(error).__name__ + ')')
    raise SystemExit(1) from None
