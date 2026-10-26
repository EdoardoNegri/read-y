# Read-y: things to try

Notes for later, in no strict order. Tick a box when it is done. The file has two main parts: features, and code and general.

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
- [ ] **Tags.** A book can have one or more tags, say "to read", "sci-fi" or "lent to Anna". Tags are picked while editing the book, where new ones can be created as well. The shelf search finds books by their tags too, as it does by title and author.
- [ ] **Something for tapping the brass title plaque.** The first idea (details of the shelf being shown) was built and then taken out again. The plaque does nothing for now. Ideas: the reading stats above, or a summary of the whole library.

## Languages and store

- [ ] **Translations.** The app has English strings only (`res/values` has no other language). Italian and German would fit, and Swiss users would see their own language. All the text is already in `strings.xml`, so it is mostly translating.
- [ ] **Keep the store files up to date.** `store/listing.md` and `privacy-policy.md` exist. After the About screen, check that both mention it: the feedback mail carries the app version and phone model (only when the user sends it), and the drink button opens an external website.

# Code and general

- [ ] **Improve the app in general.** Small polish, anything that feels rough when using it.
- [ ] **Refactor, what is left.** `Library.kt` (about 1,530 lines) could next lose the backup, the covers and the miniatures of the shelves, each of which is a block of its own that only needs a few of its private parts made visible. `ReaderActivity.kt` (about 1,000 lines) could lose the cover that ends the book and the book shutting over it. `BookOpening.kt` (about 1,470 lines) and `FlowEngine.kt` (about 1,150 lines, a third of it the page script) are the other two big ones.
