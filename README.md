# Profile Comments for Telegram

A comment wall on a Telegram profile. Friends and colleagues leave a short comment tagged as
*Recommend*, *Worked together*, *Great designer* and more. It is a wall, not a chat:

* one comment per person every 24 hours;
* only the owner replies (one reply, shown as a quote under the comment);
* visitors can't reply to each other. "Message <name>" opens a private chat for real conversations;
* the owner controls everything in **Settings → Privacy and Security → Profile Comments**:
  who can comment (Everybody / My Contacts / Nobody) and whether new comments need approval;
* the owner can pin, hide, delete and approve comments. Visitors can report a comment.

The owner and visitors see different layouts automatically. There is no preview toggle.

## What's in this folder

| Path | What it is |
|---|---|
| `android/profile-comments-android.patch` | Patch for [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (12.10.6, commit `f2908b1`). |
| `android/files/` | The same changes as plain files, laid out like the repo. |
| `ios/profile-comments-ios.patch` | Patch for [TelegramMessenger/Telegram-iOS](https://github.com/TelegramMessenger/Telegram-iOS) (commit `f1dd7a2`). |
| `ios/files/` | The same changes as plain files. |
| `docs/API.md` | Proposed MTProto methods, types and updates. |
| `prototype/profil-devori.html` | The web prototype the demo videos were made with (design reference only). |

## Android

New package `org.telegram.ui.ProfileComments`:

* `ProfileCommentsController` holds the rules (24 h limit, privacy, approval, pin, hide, report, reactions) and a
  per-account cache. Each mutating method is named after its server method in `docs/API.md`.
* `ProfileCommentsContainer` is the **Comments** tab inside `SharedMediaLayout`, next to Posts and Gifts:
  tag filters, the approval queue, the list, the empty state, the rules footer and the floating
  "Leave a comment" button for visitors.
* `ProfileCommentCell` draws one comment: avatar, tags, text, reactions, the owner's reply, approve/dismiss.
  Tap opens the menu (reactions, reply, pin, hide, message, copy, report, delete). Double tap adds ❤️.
* `ProfileCommentSheet` is the bottom sheet for writing a comment (tags, 300-character limit) or a reply.
* `ProfileCommentsSettingsActivity` is Settings → Privacy and Security → Profile Comments.

Changes to existing files are small: a new tab id in `SharedMediaLayout`, one `hasMedia` check and the tab
subtitle in `ProfileActivity`, and one row in `PrivacySettingsActivity`. Strings are in
`res/values/strings_profile_comments.xml`. Emoji go through `Emoji.replaceEmoji`, so they use Telegram's own set.

Apply and build:

```
git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git
cd Telegram && git checkout f2908b14133bbffbf7ab04f641ecb5bfaf533242
git apply profile-comments-android.patch
./gradlew :TMessagesProj_App:assembleAfatDebug
```

The patch also adds `.github/workflows/profile-comments-apk.yml`, which builds the same debug APK on GitHub Actions.

## iOS

New module `submodules/TelegramUI/Components/PeerInfo/ProfileCommentsUI`:

* `ProfileCommentsStore`: the same rules and cache as Android (thread-safe, `updates` signal).
* `ProfileCommentsPaneNode`: a `PeerInfoPaneNode` for the new `PeerInfoPaneKey.comments` tab.
* `ProfileCommentItemView`, `ProfileCommentComposeController` (sheet), and a quick-reaction strip.
* `ProfileCommentsSettingsController`: an `ItemListController` opened from Privacy and Security.

Hooks: `PeerInfoPaneKey.comments`, pane creation and tab title in `PeerInfoPaneContainerNode`, tab placement after
Posts/Gifts in `PeerInfoData`, and a `profileCommentsPrivacy` row in `PrivacyAndSecurityController`.
Strings are in `ProfileCommentsStrings.swift`, ready to move into `Localizable.strings`.

```
git apply profile-comments-ios.patch
```

then build as described in the Telegram-iOS README.

## Status

* Android: the new and changed files were type-checked with `javac` against Android 36 and the Telegram sources.
  The only errors left come from the build environment (missing AndroidX artifacts) and show up on unmodified
  files too.
* iOS: every changed Swift file was syntax-checked with a Swift parser and the API calls were checked against the
  Telegram-iOS sources. It still needs a full Xcode build.
* `DEMO_DATA` / `ProfileCommentsStore.demoData` seeds sample comments on your own wall for the demo. Turn it off
  for production.
