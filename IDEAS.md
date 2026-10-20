# Read-y: things to try

Notes for later, in no strict order. Tick a box when it is done.

## Reader features

- [ ] **Bookmarks and highlights.** There is no trace of either in the code yet. It is the feature readers miss most.
- [ ] **Search inside a book.** The shelf can be searched, but not the text of a book.
- [ ] **Look up a selected word.** Android can do this with `ACTION_PROCESS_TEXT`: the word is handed to the system dictionary or translate app. Small to build, and the app stays offline.
- [ ] **Reading stats.** Books finished this year, or time read, on a plaque in the same wood-and-brass style. It fits the theme better than a generic stats screen.

## Bookshelf

- [ ] **Something for tapping the brass title plaque.** The first idea (details of the shelf being shown) was built and then taken out again. The plaque does nothing for now. Ideas: the reading stats above, or a summary of the whole library.

## General

- [ ] **Improve the app in general.** Small polish, anything that feels rough when using it.
- [ ] **Refactor.** The biggest files are `MainActivity.kt` (about 1,450 lines) and `Library.kt` (about 1,350 lines). Splitting what they do into smaller pieces would make later changes easier.

## Open ends from the last session

- [ ] **Check the Revolut link from a browser without the app.** `revolut.me/edo_ne` failed on 2026-10-20, most likely because of a Revolut outage that day. If it still fails once Revolut is back, check the Revolut.me settings and contact their support.
- [ ] **Commit the work of that session.** Not committed yet: finding and re-finding the library folder, the finished count on shelf plaques, the About dialog (Send feedback, Rate the app, Buy me a drink).
- [ ] **Rate the app only works once the app is on Google Play.** Try it again after publishing.
