# Read-y: things to try

Notes for later, in no strict order. Tick a box when it is done. The file has two main parts: features, and performance and battery.

# Features

## Reader

- [ ] **Bookmarks and highlights.** There is no trace of either in the code yet. It is the feature readers miss most.
- [ ] **Search inside a book.** The shelf can be searched, but not the text of a book.
- [ ] **Look up a selected word.** Android can do this with `ACTION_PROCESS_TEXT`: the word is handed to the system dictionary or translate app. Small to build, and the app stays offline.
- [ ] **Reading stats.** Books finished this year, or time read, on a plaque in the same wood-and-brass style. It fits the theme better than a generic stats screen.
- [ ] **Text to speech and a sleep timer.** Reading aloud with Android's built-in voice, offline, plus a sleep timer for bedtime reading.

## Bookshelf

- [ ] **A "Currently reading" widget.** The only widget shows favorites. A single large book that opens where you left off would be a second, simpler widget.
- [ ] **Share a book out of the app** from the long-press menu.
- [ ] **Something for tapping the brass title plaque.** The first idea (details of the shelf being shown) was built and then taken out again. The plaque does nothing for now. Ideas: the reading stats above, or a summary of the whole library.

## Languages and store

- [ ] **Translations.** The app has English strings only (`res/values` has no other language). Italian and German would fit, and Swiss users would see their own language. All the text is already in `strings.xml`, so it is mostly translating.
- [ ] **Keep the store files up to date.** `store/listing.md` and `privacy-policy.md` exist. After the About screen, check that both mention it: the feedback mail carries the app version and phone model (only when the user sends it), and the drink button opens an external website.

# Performance and battery

- [ ] **Backup file: little to gain.** Checked: `Library.saveBackup` builds the backup text each time a screen is left, but only writes the file when the text changed since the last write. After reading, it always changed, so one write per book read. The only waste is the first write after the app starts, which repeats what is already on disk. A short delay before writing (so leaving the reader and the shelf in a row writes once) is the most that could be saved.
- [ ] **Thread count.** Library work runs on a small fixed pool, and each reader engine adds its own single thread. Probably fine, but look at what runs at the same time while scrolling a big shelf, in case something stalls.
- [ ] **Smaller app size.** Checked: the release APK is already about 2.0 MB (code about 0.6 MB, `resources.arsc` about 0.6 MB, the two wood textures about 0.25 MB). Little to gain. Only the 91 KB `widget_preview_image.png` could become a smaller WebP, and on Google Play the language files are split off anyway.

# Code and general

- [ ] **Improve the app in general.** Small polish, anything that feels rough when using it.
- [ ] **Refactor.** The biggest files are `MainActivity.kt` (about 1,450 lines) and `Library.kt` (about 1,350 lines). Splitting what they do into smaller pieces would make later changes easier.
- [ ] **Split `MainActivity.kt` first.** The dialogs, the About screen and the folder-access code are self-contained and could each move to their own file with little risk.

# Open ends

- [ ] **Check the Revolut link from a browser without the app.** `revolut.me/edo_ne` failed on 2026-10-20, most likely because of a Revolut outage that day. If it still fails once Revolut is back, check the Revolut.me settings and contact their support.
- [ ] **Rate the app only works once the app is on Google Play.** Try it again after publishing.
