# Read-y: Google Play store listing

Everything to paste into Play Console → *Grow users → Store presence → Main store listing*.
Limits are Google's; lengths were checked.

---

## App name (max 30)

```
Read-y: Ebook Reader & Shelf
```

## Short description (max 80)

```
Offline ebook reader for EPUB, PDF, MOBI & comics on a real wooden bookshelf.
```

## Full description (max 4000)

```
Read-y is an offline ebook reader for Android that puts your books on a real wooden bookshelf. It opens EPUB, PDF, MOBI, AZW, CBZ comics and TXT files, shows every book with its own cover, and remembers exactly where you stopped. No account, no ads, no tracking, and no internet connection: Read-y does not even ask for internet access.

YOUR BOOKS, ON A BOOKSHELF
• Every book stands on the shelf with its real cover and a brass name plate.
• Folders become shelves. Open a shelf and you step inside it; each one shows a little picture of the books it holds.
• Choose the wood of each shelf: walnut, oak, maple, cherry, mahogany or ebony.
• Drag books into your own order, or sort by title, author, last read or progress.
• Mark favourites with a star, see at a glance which books are finished, and pick up the last book with one tap.
• Search your whole library by title, author or file name.
• Add books from anywhere on your phone with "Add books": they are moved onto your shelf, never copied, so every book is kept only once.
• Give a book a new title, author or cover picture.

A READER THAT FEELS LIKE PAPER
• Turn pages like paper, tap the sides, swipe, use the volume keys, or scroll.
• Pinch to make the text bigger or smaller, and pinch to zoom into PDFs and comics.
• Adjust text size, line spacing and margins, choose from seven fonts, or import your own TTF or OTF font.
• Brightness and warmth controls plus a dark mode for reading at night.
• Chapter list, chapter and book page numbers, and a progress bar that jumps to any chapter.
• Lock the screen rotation and keep the screen on while you read.
• PDFs and comics are shown page by page, drawn sharp for your screen.

FORMATS
• EPUB (EPUB 2 and EPUB 3)
• PDF
• MOBI, AZW and PRC (Mobipocket books without DRM)
• CBZ comics
• TXT plain text

PRIVATE BY DESIGN
Read-y keeps your books in one folder, Documents/Read_y, and asks Android for access to that folder only. It never sees your photos, your downloads or anything else on your phone. Put books in the folder with any file manager or from a computer over USB, use "Add books", or open a book from another app with "Open with" or "Share": Read-y moves it onto your shelf and removes the original, so you never end up with two copies.

Your reading positions, favourites and shelf order are saved in a small file inside the Read_y folder, so when you copy the folder to a new phone, your library arrives exactly as you left it.

LIBRARY WIDGET
Put the books you are reading on your home screen, on a little wooden bookcase of their own, and open any of them with a single tap. Make it as large as you like: it grows a shelf at a time.

WHO IS READ-Y FOR?
• Readers who own DRM-free ebooks and want a calm, beautiful place to keep them.
• Students reading PDFs and papers.
• Comic readers with CBZ collections.
• Anyone who wants an ebook reader that works offline, without ads, accounts or data collection.

Read-y does not sell books and does not remove DRM. It reads the files you already have.
```

---

## Release notes, "What's new" (max 500)

```
First release of Read-y: a calm, offline ebook reader with a real wooden bookshelf. Reads EPUB, PDF, MOBI, AZW, CBZ and TXT. No ads, no account, no internet access.
```

---

## Graphics (in this folder)

| Asset | File | Play requirement |
|---|---|---|
| App icon | `icon-512.png` | 512 × 512 PNG |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 × 500 PNG/JPG |
| Phone screenshots | `screenshots/01…07` | 2–8 shots, 1080 × 2400 (9:20) |

Suggested screenshot captions, if you add text overlays later or use them as alt text:
1. `01_shelf.png`: "Your ebooks on a real wooden bookshelf"
2. `02_reading.png`: "Clean, comfortable reading"
3. `03_controls.png`: "Jump to any chapter. Illustrations included."
4. `04_shelves.png`: "Folders become shelves"
5. `05_settings.png`: "Brightness, warmth, fonts, margins"
6. `06_dark.png`: "Dark mode for night reading"
7. `07_details.png`: "Author, pages and progress at a glance"

The books shown are public-domain titles from Project Gutenberg, so the screenshots are free to publish.

---

## Store settings

- **Category:** Books & Reference
- **Tags (choose in Console):** Ebook reader, PDF reader, Comics reader, Books
- **Contact email:** *(your support address; it is shown publicly)*
- **Website:** optional, but recommended (see "Being found" below)
- **Privacy policy URL:** host `privacy-policy.md` (e.g. GitHub Pages at `https://edoardonegri.github.io/ready/privacy`) and paste the link

## App content answers

- **Ads:** No, the app contains no ads.
- **Content rating questionnaire:** category "Reference, News, or Educational"; answer *No* to violence, sexuality, language, gambling, user interaction and sharing of location. Expected rating: Everyone / PEGI 3. (The app shows the user's own files; it supplies no content itself.)
- **Target audience:** 13+ (choosing under-13 brings in the Families policy, which you don't need).
- **Data safety:** *No data collected* and *No data shared*. The app has no internet permission. Answer "No" to "Does your app collect or share any of the required user data types?"
- **Government / financial / health / news app:** No.
- **Permissions declaration:** none needed. The app no longer requests `MANAGE_EXTERNAL_STORAGE` (All files access), which Google rejects for ebook readers.

## Before you upload

1. Raise `versionCode`/`versionName` in `app/build.gradle.kts` for each upload.
2. Build a signed bundle: *Android Studio → Build → Generate Signed App Bundle*, then create an upload keystore and **back it up**. Play App Signing holds the real key.
3. New personal developer accounts must run a **closed test with at least 12 testers for 14 days** before production access is granted.

---

## Being found when people ask an AI for a reading app

Nobody can guarantee an app comes up first when someone asks ChatGPT, Claude, Gemini or Perplexity "what's the best ebook reader for Android?". Those assistants answer from what the web says about an app. What you can do is make Read-y easy to describe and easy to find:

1. **One clear sentence, repeated everywhere.** The listing opens with "Read-y is an offline ebook reader for Android that puts your books on a real wooden bookshelf. It opens EPUB, PDF, MOBI, AZW, CBZ comics and TXT files…". Use exactly that sentence on your website, GitHub README, Reddit posts and so on. Consistent wording is what models pick up.
2. **Answer the real questions people ask.** The description states plainly: offline, no ads, no account, no tracking, which formats, private folder access, widget, fonts. These are the comparison points AI answers list.
3. **Be present outside the Play Store.** Models cite web pages, not Play listings alone:
   - a one-page website or GitHub Pages site with the description, screenshots and an FAQ
   - an entry on AlternativeTo (as an alternative to Moon+ Reader, ReadEra, Librera, Lithium)
   - posts in r/androidapps, r/ebooks and r/Calibre ("I made an offline ebook reader with a wooden bookshelf")
   - if you open-source it, F-Droid, which tech-savvy reviewers and models trust a lot
4. **Get reviews.** Ask your closed-test readers to leave honest Play reviews; ratings and review text feed into both Play search and AI answers.
5. **Stay within Play policy.** Don't put "best", "#1" or "top" in the title, icon or short description, and don't stuff repeated keywords. Google rejects listings for this, and it does nothing for AI answers.
