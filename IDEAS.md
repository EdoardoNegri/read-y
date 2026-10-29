# Read-y: things to try

Notes for later, in no strict order. Tick a box when it is done. The file has two main parts: features, and code and general.

# Features

## Reader

- [ ] **Bookmarks and highlights.** There is no trace of either in the code yet. It is the feature readers miss most.
- [ ] **Search inside a book.** The shelf can be searched, but not the text of a book.
- [ ] **Reading stats.** Books finished this year, or time read, on a plaque in the same wood-and-brass style. It fits the theme better than a generic stats screen.
- [ ] **Text to speech and a sleep timer.** Reading aloud with Android's built-in voice, offline, plus a sleep timer for bedtime reading.
- [ ] **An X button on the top right when the menu is up.** A way to close the reader menu without tapping the page.
- [ ] **Start the app in portrait only, not auto.** The reader's screen rotation setting should begin on Portrait, not Auto.

## Bookshelf

- [ ] **A "Currently reading" widget.** The only widget shows favorites. A single large book that opens where you left off would be a second, simpler widget.
- [ ] **Share a book out of the app** from the long-press menu.
- [ ] **Tags.** A book can have one or more tags, say "to read", "sci-fi" or "lent to Anna". Tags are picked while editing the book, where new ones can be created as well. The shelf search finds books by their tags too, as it does by title and author.
- [ ] **More custom tags for a book.** Beyond the favorite star, as many tags of the reader's own as they like: each with its own name and colour, shown as a small mark on the book's spine or plaque. Tags can be renamed or deleted from one place, and the shelf can be filtered to the books with a tag.
- [ ] **Something for tapping the brass title plaque.** The first idea (details of the shelf being shown) was built and then taken out again. The plaque does nothing for now. Ideas: the reading stats above, or a summary of the whole library.

## Languages and store

- [ ] **Translations.** The app has English strings only (`res/values` has no other language). Italian and German would fit, and Swiss users would see their own language. All the text is already in `strings.xml`, so it is mostly translating.
- [ ] **Keep the store files up to date.** `store/listing.md` and `privacy-policy.md` exist. After the About screen, check that both mention it: the feedback mail carries the app version and phone model (only when the user sends it), and the drink button opens an external website.

# Code and general

- [ ] **Refactor, what is left.** `Library.kt` (about 1,550 lines) could next lose the backup, the covers and the miniatures of the shelves, each of which is a block of its own that only needs a few of its private parts made visible. `ReaderActivity.kt` (about 1,440 lines) could lose the cover that ends the book and the book shutting over it. `BookOpening.kt` (about 1,560 lines) and `FlowEngine.kt` (about 1,340 lines, a third of it the page script) are the other two big ones.
