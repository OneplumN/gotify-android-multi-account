# Multi-Account Testing Checklist

Use this checklist before releasing multi-account support.

## Automated Checks

Run with Java 17 available:

```bash
./gradlew :app:assembleDevelopment
./gradlew :app:testDevelopmentUnitTest
./gradlew :app:lintDevelopment
```

## Install and Migration

- Fresh install opens the login screen.
- Upgrade from a single-account install migrates the existing URL, client token, user, server version, and SSL settings into one active account.
- Upgrade does not delete legacy preferences until the migrated account has been verified manually.

## Login

- Basic auth login creates a new account and makes it active.
- OIDC login creates a new account and makes it active.
- Adding a second account does not mutate the first account's URL, token, user, or SSL settings.
- Logging in again with the same URL and client token updates the existing account instead of creating a duplicate.

## Account Switching

- The drawer shows the current account's user, URL, and version.
- Add account opens the login screen without removing the active account.
- Switch account lists all saved accounts.
- Switching account restarts the app flow through `InitializationActivity`.
- Switching account restarts `WebSocketService` for the new active account.
- App list and message list reload from the selected account.

## Account Removal

- Removing the active account attempts server logout or client deletion for that account.
- Removing one account while another exists switches to the remaining account.
- Removing the final account opens the login screen.
- Removing an account stops the old `WebSocketService` connection.

## Notifications

- A notification sent to the active account appears normally.
- After switching accounts, a late callback from the previous WebSocket does not add messages to the new account view.
- Foreground connection notification reflects the selected account's server.
- Android 13 notification permission flow still works.
- Android 14 exact alarm permission flow still works.

## SSL and Certificates

- Disable SSL validation during login is stored on the newly added account.
- Custom CA certificate during login is stored on the newly added account.
- Client certificate and password during login are stored on the newly added account.
- Adding an account with custom SSL settings does not change an existing account.

## Sharing

- Android share sheet opens `ShareActivity` for the active account.
- `ShareActivity` loads applications from the active account.
- Pushing a shared message sends through the selected app token on the active account.

## Boot

- Device boot starts `WebSocketService` when an active account exists.
- Device boot does not start `WebSocketService` when all accounts have been removed.

