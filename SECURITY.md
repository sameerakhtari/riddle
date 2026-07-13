# Security

## Reporting

Do not publish API keys, tokens, private diary pages, or complete diagnostic exports in a public issue. Remove personal content and secrets before sharing logs.

## Credentials

The Android app stores configured secrets with Android Keystore-backed encryption. A rooted or compromised device can weaken these protections. A private proxy is recommended for provider keys with meaningful billing access.

Never commit:

- provider API keys;
- backend `.env` files;
- signing keystores or passwords;
- Hugging Face tokens;
- private model URLs containing credentials.

## Supported scope

Security fixes are applied to the latest `main` release and the active `development` branch.
