# Update System

## Overview

Sigma Bridge has an in-app update checker for the Android application. The current implementation uses GitHub Releases as the update authority and Android `DownloadManager` for APK downloads.

## Source of truth

The checker calls:

```text
https://api.github.com/repos/EngMohamedAshraf1/sigma-bridge-android1/releases/latest
```

The response is expected to contain:

- `tag_name`
- `name`
- `html_url`
- an APK asset in `assets`

The checker selects the first asset whose filename ends with `.apk`.

## Version comparison

The installed version comes from:

```text
BuildConfig.VERSION_NAME
```

The remote version comes from GitHub `tag_name`.

The implementation normalizes versions by:

1. trimming whitespace;
2. removing a leading `v`;
3. dropping any suffix after `-`;
4. splitting on `.`;
5. comparing numeric components in order.

An update is available only when:

```text
remoteVersion > installedVersion
```

Therefore the release tag and the embedded application version must be kept synchronized.

## Current stable release: v0.8.7

The current Private Chat stable release is:

```text
Release:  Sigma Bridge v0.8.7 — Private Chat Stable
Tag:      v0.8.7-private-chat-stable
Commit:   37054fa02f8a91d2f4e582b87756479013e73bb8
Version:  versionName 0.8.7 / versionCode 7
APK:      sigma-bridge.apk
SHA-256:  5f671b7d99957cbfc411c72ee558cfdd0c0a22d3067d0719f8cf067864b004b0
```

The release is published on GitHub and is marked as the latest stable release. Its scope is Private Chat only; Telegram functionality was not changed.

## Important v0.8.6 incident

The first `v0.8.6` GitHub Release was created while the APK project metadata still declared:

```text
versionCode = 5
versionName = 0.8.5
```

The checker was correctly comparing `0.8.6` from GitHub against `0.8.5` from the installed APK, so the application repeatedly showed the update banner even after the user believed 0.8.6 was installed.

The project was subsequently corrected to:

```text
versionCode = 6
versionName = 0.8.6
```

The correction was committed in `b2eea8d0d4fdb94827ee72476b01d08d1e954a88`.

### Release rule

Never publish an APK under release tag `vX.Y.Z` unless the APK itself reports `X.Y.Z` through `BuildConfig.VERSION_NAME`.

For a new release:

```text
1. Change versionCode/versionName.
2. Build the APK.
3. Verify the generated APK's embedded version.
4. Create/publish the Git tag.
5. Attach the matching APK to the release.
6. Install the APK and verify that no same-version update banner appears.
```

For v0.8.7, this process produced `versionCode 7` / `versionName 0.8.7`, and the release asset was attached to `v0.8.7-private-chat-stable`.

## UI

`MainActivity` calls `UpdateManager.checkOnLaunch(BuildConfig.VERSION_NAME)`.

If the checker returns `updateAvailable = true`, the UI exposes `UpdateBanner` at the bottom of the main content.

The banner shows the latest version and provides an Update Now action.

## Download flow

`UpdateManager.downloadAndInstall(...)` performs:

```text
Update button
   |
   v
validate APK URL
   |
   v
check Android install-from-unknown-source permission
   |
   v
DownloadManager.enqueue()
   |
   v
ACTION_DOWNLOAD_COMPLETE
   |
   v
verify download status
   |
   v
obtain downloaded URI
   |
   v
ACTION_VIEW + application/vnd.android.package-archive
   |
   v
Android package installer
```

The downloaded filename comes from the GitHub asset. If GitHub does not provide one, the fallback is:

```text
SigmaBridge-v<latestVersion>.apk
```

## Unknown-source installation permission

On Android O and newer, the app checks `canRequestPackageInstalls()`. If permission is missing, it sends the user to the app-specific unknown-app-source settings screen and records an `INSTALL_PERMISSION_REQUIRED` state.

The update flow does not silently bypass Android's package-installation security restrictions.

## Error states

The current manager reports internal state values such as:

```text
INSTALL_PERMISSION_REQUIRED
DOWNLOAD_MANAGER_UNAVAILABLE
DOWNLOAD_FAILED
DOWNLOAD_URI_UNAVAILABLE
INSTALL_FAILED
```

These values are intended for mapping into a user-facing message. Do not expose raw HTTP, filesystem, or stack-trace details to normal chat users.

## Current limitations

The current checker is intentionally simple:

- one latest GitHub release is queried;
- the first matching APK asset is selected;
- there is no signed-manifest metadata separate from GitHub release data;
- there is no delta update mechanism;
- the application relies on Android's package installer for the actual install.

Do not add complexity such as background auto-updating, alternate mirrors, or silent installation without an explicit product/security decision.

## Release checklist

Before publishing an update:

```text
[ ] versionCode increased
[ ] versionName matches release tag
[ ] Debug build tested
[ ] Release APK built
[ ] APK installed over previous version
[ ] Update checker tested from an older version
[ ] Same-version update banner tested and absent
[ ] APK filename ends in .apk
[ ] Release notes describe only changes actually shipped
[ ] No secrets are attached or documented
```
