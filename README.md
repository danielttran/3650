# Grant Horner Audio Bible

This is an Android application designed to facilitate the **Professor Grant Horner's Bible Reading System** utilizing a comprehensive suite of high-quality MP3 audio files.

## The Grant Horner Reading Method

The Grant Horner Bible Reading System is a highly effective, rigorous reading plan designed to familiarize the reader with the entire text of the Bible by reading from 10 different lists (categories) of books every single day. 

By reading one chapter from each of the 10 lists daily, you will read 10 chapters a day. Because each list has a different number of chapters, they all loop at different rates. This creates a continuously shifting matrix of scriptures, meaning you will never read the exact same combination of 10 chapters twice.

### The 10 Standard Lists:
1. **Gospels:** Matthew, Mark, Luke, John (89 chapters - loops every 89 days)
2. **Pentateuch:** Genesis, Exodus, Leviticus, Numbers, Deuteronomy (187 chapters)
3. **Pauline Epistles & Hebrews:** Romans, 1 & 2 Corinthians, Galatians, Ephesians, Philippians, Colossians, Hebrews (78 chapters)
4. **General Epistles & Revelation:** 1 & 2 Thessalonians, 1 & 2 Timothy, Titus, Philemon, James, 1 & 2 Peter, 1, 2, & 3 John, Jude, Revelation (65 chapters)
5. **Wisdom:** Job, Ecclesiastes, Song of Songs (62 chapters)
6. **Psalms:** Psalms (150 chapters)
7. **Proverbs:** Proverbs (31 chapters - matches the typical month)
8. **History:** Joshua, Judges, Ruth, 1 & 2 Samuel, 1 & 2 Kings, 1 & 2 Chronicles, Ezra, Nehemiah, Esther (249 chapters)
9. **Prophets:** Isaiah, Jeremiah, Lamentations, Ezekiel, Daniel, Hosea, Joel, Amos, Obadiah, Jonah, Micah, Nahum, Habakkuk, Zephaniah, Haggai, Zechariah, Malachi (250 chapters)
10. **Acts:** Acts (28 chapters)

*When you finish a list, you simply start over at the beginning of that specific list the next day. The app handles this calculation automatically.*

## App Architecture

This app is built natively for Android using:
* **Jetpack Compose:** For a modern, reactive UI.
* **Room Database:** For persistent state and reading progression tracking.
* **Media3:** A background `MediaLibraryService` orchestrates audio playback via IPC, automatically advancing the daily database task when a chapter completes.
* **Hilt:** For Dependency Injection.
* **Storage Access Framework (SAF):** Audio files are loaded at runtime from a folder the user selects on their device. This approach removes the need to bundle the large (~4.6 GB) audio collection inside the app.

## Development & Deployment

Because audio is provided by the user via SAF, there are no large asset packs to manage and standard ADB installation is fast.

The app includes robust concurrency handling for audio playback, proper error recovery, tablet support, and a clean Material 3 UI.