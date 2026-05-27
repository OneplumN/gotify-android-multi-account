# Android Multi-Account Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add multi-account support to Gotify Android by allowing users to save multiple Gotify server/client-token profiles, switch the active profile, and preserve the current single-WebSocket behavior for the first release.

**Architecture:** Introduce an account storage layer that owns all persisted Gotify account records and a single active account id. Keep `Settings` as a compatibility facade over the active account first, then update login, initialization, messages, share, logout, and service restart flows to use account-aware semantics. Defer true parallel WebSocket notification delivery to a second phase after the UI and storage model are stable.

**Tech Stack:** Android Kotlin, SharedPreferences JSON for first implementation, Gson, Retrofit/OpenAPI client, OkHttp WebSocket, XML/ViewBinding UI, Gradle JVM unit tests.

---

## Scope

Phase 1 delivers:

- Save multiple Gotify accounts locally.
- Add new account without overwriting existing accounts.
- Switch active account from the navigation drawer.
- Remove/logout only the active account.
- Restart the existing single `WebSocketService` when the active account changes.
- Keep all current server API behavior, OIDC login, SSL options, notifications, message list, and share flow working for the active account.

Phase 1 explicitly does not deliver:

- Receiving push notifications from all accounts at the same time.
- Showing a merged inbox across accounts.
- Syncing account metadata across devices.
- Encrypting tokens beyond the app's current local persistence model.

Phase 2 proposal is included at the end for parallel WebSocket delivery.

## Key Existing Files

- `app/src/main/kotlin/com/github/gotify/Settings.kt`
- `app/src/main/kotlin/com/github/gotify/api/ClientFactory.kt`
- `app/src/main/kotlin/com/github/gotify/login/LoginViewModel.kt`
- `app/src/main/kotlin/com/github/gotify/login/LoginActivity.kt`
- `app/src/main/kotlin/com/github/gotify/init/InitializationActivity.kt`
- `app/src/main/kotlin/com/github/gotify/init/BootCompletedReceiver.kt`
- `app/src/main/kotlin/com/github/gotify/messages/MessagesActivity.kt`
- `app/src/main/kotlin/com/github/gotify/messages/MessagesModel.kt`
- `app/src/main/kotlin/com/github/gotify/service/WebSocketService.kt`
- `app/src/main/kotlin/com/github/gotify/sharing/ShareActivity.kt`
- `app/src/main/res/menu/messages_menu.xml`
- `app/src/main/res/layout/nav_header_drawer.xml`
- `app/src/main/res/values/strings.xml`

## Data Model

Create:

- `app/src/main/kotlin/com/github/gotify/accounts/GotifyAccount.kt`
- `app/src/main/kotlin/com/github/gotify/accounts/AccountStore.kt`

Use this model:

```kotlin
internal data class GotifyAccount(
    val id: String,
    val label: String,
    val url: String,
    val token: String,
    val username: String?,
    val admin: Boolean,
    val serverVersion: String,
    val validateSSL: Boolean,
    val caCertPath: String?,
    val clientCertPath: String?,
    val clientCertPassword: String?
)
```

Storage keys:

- `accounts_json`: serialized `List<GotifyAccount>`
- `active_account_id`: selected account id
- Legacy keys remain readable during migration: `url`, `token`, `username`, `admin`, `version`, `validateSSL`, `caCertPath`, `clientCertPath`, `clientCertPass`

Account id:

```kotlin
UUID.randomUUID().toString()
```

Default label:

```text
<username or UNKNOWN> @ <host>
```

## Task 1: Add Unit Test Foundation

**Files:**

- Modify: `app/build.gradle.kts`
- Create: `app/src/test/kotlin/com/github/gotify/accounts/AccountStoreTest.kt`

**Step 1: Add test dependencies**

Add:

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("androidx.test:core:1.6.1")
testImplementation("org.robolectric:robolectric:4.14.1")
```

**Step 2: Create an empty placeholder test**

```kotlin
package com.github.gotify.accounts

import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStoreTest {
    @Test
    fun placeholder() {
        assertTrue(true)
    }
}
```

**Step 3: Run test**

Run:

```bash
./gradlew :app:testDevelopmentUnitTest
```

Expected:

- Fails locally if Java 17 is missing.
- Passes once Java 17 is installed.

**Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/test/kotlin/com/github/gotify/accounts/AccountStoreTest.kt
git commit -m "test: add unit test foundation"
```

## Task 2: Add Account Model and Store

**Files:**

- Create: `app/src/main/kotlin/com/github/gotify/accounts/GotifyAccount.kt`
- Create: `app/src/main/kotlin/com/github/gotify/accounts/AccountStore.kt`
- Modify: `app/src/test/kotlin/com/github/gotify/accounts/AccountStoreTest.kt`

**Step 1: Write tests**

Cover:

- Saving first account sets it active.
- Saving second account preserves active account unless explicitly switched.
- `setActiveAccount(id)` changes active account.
- Removing active account selects another account.
- Removing final account clears active id.
- Legacy single-account settings migrate into one account.

**Step 2: Implement `GotifyAccount`**

Use the model defined above.

**Step 3: Implement `AccountStore`**

Required API:

```kotlin
internal class AccountStore(context: Context) {
    fun all(): List<GotifyAccount>
    fun active(): GotifyAccount?
    fun activeAccountId(): String?
    fun save(account: GotifyAccount, makeActive: Boolean = false)
    fun setActiveAccount(id: String): Boolean
    fun remove(id: String): GotifyAccount?
    fun hasAccounts(): Boolean
    fun migrateLegacyIfNeeded()
}
```

Implementation notes:

- Store in `context.getSharedPreferences("gotify_accounts", Context.MODE_PRIVATE)`.
- Read/write JSON with `Utils.JSON`.
- `migrateLegacyIfNeeded()` reads old `Settings` keys from `context.getSharedPreferences("gotify", Context.MODE_PRIVATE)`.
- Only migrate when `accounts_json` is empty and legacy `token` is non-empty.
- Do not delete legacy keys in this task.

**Step 4: Run tests**

Run:

```bash
./gradlew :app:testDevelopmentUnitTest --tests "com.github.gotify.accounts.AccountStoreTest"
```

Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/accounts app/src/test/kotlin/com/github/gotify/accounts/AccountStoreTest.kt
git commit -m "feat: add Gotify account store"
```

## Task 3: Refactor Settings Into Active Account Facade

**Files:**

- Modify: `app/src/main/kotlin/com/github/gotify/Settings.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/GotifyApplication.kt`
- Modify: `app/src/test/kotlin/com/github/gotify/accounts/AccountStoreTest.kt`

**Step 1: Add facade tests**

Test that:

- `Settings(context).tokenExists()` is true when active account exists.
- `Settings.url`, `Settings.token`, `Settings.user`, `Settings.serverVersion`, and `Settings.sslSettings()` read active account values.
- `Settings.clear()` removes only the active account.

**Step 2: Update `Settings`**

Keep current public properties to avoid rewriting the whole app at once:

```kotlin
private val accountStore = AccountStore(context)
```

Rules:

- `url` getter reads `accountStore.active()?.url ?: legacyUrl`.
- `token` getter reads `accountStore.active()?.token ?: legacyToken`.
- `setUser(name, admin)` updates active account with username/admin.
- `serverVersion` setter updates active account.
- SSL setters update active account when one exists; otherwise write legacy login-time values.
- `clear()` removes active account, not all accounts.

**Step 3: Trigger migration early**

In `GotifyApplication.onCreate()`, call:

```kotlin
AccountStore(this).migrateLegacyIfNeeded()
```

before the legacy CA certificate migration.

**Step 4: Run tests**

Run:

```bash
./gradlew :app:testDevelopmentUnitTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/Settings.kt app/src/main/kotlin/com/github/gotify/GotifyApplication.kt app/src/test
git commit -m "feat: back Settings with active account"
```

## Task 4: Make Login Create or Update Accounts

**Files:**

- Modify: `app/src/main/kotlin/com/github/gotify/login/LoginViewModel.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/login/LoginState.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/login/LoginActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Add account save helper**

In `LoginViewModel`, add a private method:

```kotlin
private fun saveLoggedInAccount(token: String, username: String? = null, admin: Boolean = false)
```

It should:

- Build a `GotifyAccount` from current URL, token, SSL settings, `gotifyInfo.version`, and user info.
- If an existing account has the same `url + token`, update it.
- Otherwise insert a new account.
- Make it active.

**Step 2: Basic auth flow**

Currently `createClient()` writes:

```kotlin
settings.token = c.token
```

Replace with:

- Fetch current user if needed, or reuse the successful `currentUser()` response from `login()`.
- Save account via `AccountStore`.
- Emit `LoginSuccess`.

**Step 3: OIDC flow**

Currently `handleOidcCallback()` writes:

```kotlin
settings.token = response.token
```

Replace with:

- Save account using `response.token` and `response.user`.
- Emit `LoginSuccess`.

**Step 4: Login success navigation**

Keep existing navigation to `InitializationActivity`. It will authenticate the new active account and start the service.

**Step 5: Manual test**

Use two Gotify servers or two client tokens:

- Login to account A.
- Logout should not be tested yet.
- Add account B after Task 6 UI is available.

**Step 6: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/login app/src/main/res/values/strings.xml
git commit -m "feat: save logins as accounts"
```

## Task 5: Update App Startup and Boot Behavior

**Files:**

- Modify: `app/src/main/kotlin/com/github/gotify/init/InitializationActivity.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/init/BootCompletedReceiver.kt`

**Step 1: Use account store in startup**

In `InitializationActivity.onCreate()`:

- Call migration before checking token.
- If no active account but accounts exist, select first account.
- If no account exists, show login.
- Otherwise continue existing permission and authentication flow.

**Step 2: Use account store on boot**

In `BootCompletedReceiver.onReceive()`:

- Start service only when `AccountStore(context).active() != null`.

**Step 3: Verify**

Run:

```bash
./gradlew :app:assembleDevelopment
```

Expected: PASS.

Manual:

- Fresh install opens login.
- Existing single-account install migrates and opens messages.
- Reboot receiver starts service when an active account exists.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/init
git commit -m "feat: start app from active account"
```

## Task 6: Add Account Switcher UI in Drawer

**Files:**

- Modify: `app/src/main/res/menu/messages_menu.xml`
- Modify: `app/src/main/res/layout/nav_header_drawer.xml`
- Modify: `app/src/main/kotlin/com/github/gotify/messages/MessagesActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Add strings**

Add:

- `accounts`
- `add_account`
- `switch_account`
- `remove_account`
- `remove_account_confirm`

**Step 2: Add drawer menu items**

Add menu items near settings/logout:

- Add account
- Switch account
- Remove current account

Use existing Material dialog style. Avoid adding a new screen in this task.

**Step 3: Implement Add Account**

In `MessagesActivity.onNavigationItemSelected()`:

- `add_account` starts `LoginActivity`.
- Do not clear existing account.

**Step 4: Implement Switch Account**

Show a `MaterialAlertDialogBuilder.setSingleChoiceItems()` dialog:

- Items are `AccountStore.all().map { it.label }`.
- Selecting an account calls `AccountStore.setActiveAccount(id)`.
- Then call a local `restartForActiveAccount()` helper.

`restartForActiveAccount()` should:

- Stop `WebSocketService`.
- Evict Coil cache.
- Start `InitializationActivity`.
- Finish current activity.

**Step 5: Implement Remove Current Account**

For now, reuse existing server logout logic for the active account, then remove active account locally. If other accounts remain, switch to the next account and restart; otherwise open login.

**Step 6: Manual test**

- Login account A.
- Add account B.
- Switch A/B.
- Confirm drawer header updates user/url/version.
- Confirm messages reload per active account.

**Step 7: Commit**

```bash
git add app/src/main/res/menu/messages_menu.xml app/src/main/res/layout/nav_header_drawer.xml app/src/main/kotlin/com/github/gotify/messages/MessagesActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat: add account switcher"
```

## Task 7: Make Logout Remove Only Active Account

**Files:**

- Modify: `app/src/main/kotlin/com/github/gotify/messages/MessagesActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Rename user-facing copy**

Keep `Logout` if preferred, but change confirm text to make scope clear:

```text
Remove this account from Gotify Android?
```

**Step 2: Update `deleteClientAndNavigateToLogin()`**

Refactor into:

```kotlin
private fun removeActiveAccount(deleteServerClient: Boolean)
private fun navigateAfterAccountRemoval()
```

Rules:

- Stop service before changing active account.
- Try server logout/client deletion only for current active account.
- Remove current account locally.
- If another account exists, set it active and start `InitializationActivity`.
- If none exists, start `LoginActivity`.

**Step 3: Verify**

Manual:

- With A and B saved, remove A.
- App switches to B.
- Service reconnects to B.
- Remove B.
- App opens login.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/messages/MessagesActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat: remove only active account on logout"
```

## Task 8: Ensure Active Account Isolation in Runtime Flows

**Files:**

- Modify: `app/src/main/kotlin/com/github/gotify/messages/MessagesModel.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/messages/MessagesActivity.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/sharing/ShareActivity.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/CoilInstance.kt`

**Step 1: Messages**

On account switch, `MessagesActivity` is restarted, so `MessagesModel` can remain single-active-account. Add no global cache keyed only by app id.

**Step 2: Share flow**

Confirm `ShareActivity` uses `Settings(this)` after account switch. If launched while no active account exists, keep current toast and finish.

Optional but recommended: show active account label in `ShareActivity` toolbar subtitle.

**Step 3: Coil/image cache**

Current `CoilInstance.getIcon()` resolves images using `Settings(context).url`. On account switch, call `CoilInstance.evict(this)` before restart. If image mix-ups are observed, add account id to Coil cache keys later.

**Step 4: Verify**

Manual:

- Switch account.
- Open app drawer and confirm apps list belongs to selected server.
- Use Android share sheet and confirm push uses selected server.
- Confirm app icons resolve from selected server URL.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/messages app/src/main/kotlin/com/github/gotify/sharing app/src/main/kotlin/com/github/gotify/CoilInstance.kt
git commit -m "fix: keep runtime flows scoped to active account"
```

## Task 9: Harden WebSocket Service for Account Changes

**Files:**

- Modify: `app/src/main/kotlin/com/github/gotify/service/WebSocketService.kt`
- Modify: `app/src/main/kotlin/com/github/gotify/service/WebSocketConnection.kt`

**Step 1: Capture active account at service start**

In `WebSocketService.onCreate()` or `startPushService()`:

- Read active account once.
- If no active account exists, stop foreground/service safely.

**Step 2: Include account id internally**

Add:

```kotlin
private var activeAccountId: String? = null
```

Set it from `AccountStore.active()?.id`.

**Step 3: Ignore stale callbacks**

Before showing notifications or broadcasting new messages, check that current active account id still equals `activeAccountId`. If not, close connection and stop service.

**Step 4: Verify**

Manual:

- Start account A.
- Switch to account B while A service is connected.
- Confirm A connection closes and B connection starts.
- Send message to A after switch; it should not appear in B's list.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/github/gotify/service
git commit -m "fix: guard websocket against account switches"
```

## Task 10: Add Migration and Regression Checklist

**Files:**

- Create: `docs/multi-account-testing.md`
- Modify: `metadata/en-US/full_description.txt` only if release notes mention multi-account support.

**Step 1: Document test matrix**

Include:

- Fresh install.
- Upgrade from single-account install.
- Add second account.
- Switch account.
- Remove active account.
- Remove final account.
- Basic auth login.
- OIDC login.
- Custom CA cert.
- Client certificate.
- Share-to-Gotify.
- Boot receiver.
- Android 13 notification permission.
- Android 14 exact alarm permission.

**Step 2: Run static checks**

Run:

```bash
./gradlew :app:assembleDevelopment
./gradlew :app:testDevelopmentUnitTest
./gradlew :app:lintDevelopment
```

Expected: PASS.

**Step 3: Commit**

```bash
git add docs/multi-account-testing.md metadata/en-US/full_description.txt
git commit -m "docs: add multi-account testing checklist"
```

## Phase 2: Parallel Notification Delivery

Do this only after Phase 1 is stable.

### Architecture

Change `WebSocketService` from one active connection to a connection manager:

```text
WebSocketService
  AccountStore.all()
  Map<accountId, WebSocketConnection>
  Map<accountId, MissedMessageUtil>
  Map<accountId, AtomicLong lastReceivedMessage>
  Map<accountId, appIdToApp>
```

### Required Changes

- Add account id to `NEW_MESSAGE_BROADCAST`.
- Add account id to notification `PendingIntent`.
- Open `MessagesActivity` with an account id extra and switch active account before rendering.
- Prefix notification titles or content with account label when more than one account is enabled.
- Add per-account notification channel groups.
- Add account-level setting: `receiveNotifications`.
- Let users disable background notifications per account.

### Risk

This is much more invasive than Phase 1 because it changes service lifecycle, notification routing, message list routing, and account activation semantics. It should be a separate PR.

## Final Verification

Run:

```bash
git status --short
./gradlew :app:assembleDevelopment
./gradlew :app:testDevelopmentUnitTest
./gradlew :app:lintDevelopment
```

Manual smoke test with two accounts:

1. Fresh install opens login.
2. Login to account A.
3. Receive notification from A.
4. Add account B.
5. Switch to B.
6. Receive notification from B.
7. Share text to Gotify; verify it sends through B.
8. Switch back to A.
9. Remove A.
10. Confirm B remains usable.
11. Remove B.
12. Confirm login screen appears.

## Rollback Plan

If account migration causes problems:

- Leave legacy keys untouched during migration.
- Add a temporary fallback in `Settings` to read legacy `gotify` preferences when `AccountStore.active()` is null.
- Disable account switch UI by hiding menu items while keeping single-account behavior intact.

