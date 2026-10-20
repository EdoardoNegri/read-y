# Read-y: things to try

Notes for later, in no strict order. Tick a box when it is done.

## Reader features

- [ ] **Bookmarks and highlights.** There is no trace of either in the code yet. It is the feature readers miss most.
- [ ] **Search inside a book.** The shelf can be searched, but not the text of a book.
- [ ] **Look up a selected word.** Android can do this with `ACTION_PROCESS_TEXT`: the word is handed to the system dictionary or translate app. Small to build, and the app stays offline.
- [ ] **Reading stats.** Books finished this year, or time read, on a plaque in the same wood-and-brass style. It fits the theme better than a generic stats screen.
- [ ] **Text to speech and a sleep timer.** Reading aloud with Android's built-in voice, offline, plus a sleep timer for bedtime reading.

## Bookshelf

- [ ] **Launcher shortcut "Continue reading".** Long-pressing the app icon would open the last book directly. There are no shortcuts yet. The widget and the continue button on the shelf already do something similar.
- [ ] **Duplicate warning when importing.** No duplicate handling exists. Adding the same book twice from the share sheet silently gives two copies.
- [ ] **A "Currently reading" widget.** The only widget shows favorites. A single large book that opens where you left off would be a second, simpler widget.
- [ ] **Share a book out of the app** from the long-press menu.
- [ ] **Something for tapping the brass title plaque.** The first idea (details of the shelf being shown) was built and then taken out again. The plaque does nothing for now. Ideas: the reading stats above, or a summary of the whole library.

## General

- [ ] **Improve the app in general.** Small polish, anything that feels rough when using it.
- [ ] **Refactor.** The biggest files are `MainActivity.kt` (about 1,450 lines) and `Library.kt` (about 1,350 lines). Splitting what they do into smaller pieces would make later changes easier.
- [ ] **Split `MainActivity.kt` first.** The dialogs, the About screen and the folder-access code are self-contained and could each move to their own file with little risk.
- [ ] **Fix the lint and compiler warnings.** A quick pass now makes the refactoring safer later.
- [ ] **Crash and slowness check before release.** Run the release build with a few large PDFs, a big EPUB and hundreds of books, and see where it gets slow. The app lists folders through the system, which can be slow with many files.
- [ ] **Translations.** The app has English strings only (`res/values` has no other language). Italian and German would fit, and Swiss users would see their own language. All the text is already in `strings.xml`, so it is mostly translating.
- [ ] **Keep the store files up to date.** `store/listing.md` and `privacy-policy.md` exist. After the About screen, check that both mention it: the feedback mail carries the app version and phone model (only when the user sends it), and the drink button opens an external website.

## Performance and battery

- [ ] **Write the backup less often.** The backup file is written every time a screen is left (`Library.backUp` in `onPause`), and each write goes through the system's document provider, which is slow. Writing it only when something changed, or after a short delay, would save storage work and a little battery.
- [ ] **Faster cold start.** On every start the app lists the library folder and reads the backup file before it shows anything (`Library.warmUp`). Show the last known shelf first and refresh afterwards. It already does this for shelves it has listed before.
- [ ] **Thread count.** Library work runs on a small fixed pool, and each reader engine adds its own single thread. Probably fine, but look at what runs at the same time while scrolling a big shelf, in case something stalls.
- [ ] **Work on the main thread while showing a shelf.** `refresh()` in `MainActivity.kt` calls things like `Store.mkdirs(root)` and `current.isFolder` directly, which ask Android's slow document provider on the main thread. That can cause small stutters when opening a shelf. Switch on StrictMode in debug builds to see exactly where. It costs nothing in the release app.
- [ ] **Smaller app size.** The release build is already shrunk. Check what the APK contains (Android Studio's APK analyzer) for large images such as the wood textures, and convert them to smaller WebP files.

## Safety checks before release

- [ ] **Confirm the app has no internet permission.** Libraries can add permissions silently. Dump the merged permissions of the release APK and check. The About text says the app has no internet access, so it should be true.
- [ ] **Check the EPUB web view settings.** EPUBs can contain scripts. Confirm JavaScript is switched off and the web view cannot read other files. Not checked yet.
- [ ] **Review what the cloud backup includes.** `allowBackup` is on, so Android may back up the app's data to Google. The privacy policy should say what that covers. The rules files are in `res/xml`.

## Open ends from the last session

- [ ] **Check the Revolut link from a browser without the app.** `revolut.me/edo_ne` failed on 2026-10-20, most likely because of a Revolut outage that day. If it still fails once Revolut is back, check the Revolut.me settings and contact their support.
- [ ] **Commit the work of that session.** Not committed yet: finding and re-finding the library folder, the finished count on shelf plaques, the About dialog (Send feedback, Rate the app, Buy me a drink).
- [ ] **Rate the app only works once the app is on Google Play.** Try it again after publishing.
