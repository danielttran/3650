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
* **ExoPlayer / Media3:** A background service orchestrates audio playback via IPC, automatically advancing the daily database task when a chapter completes.
* **Hilt:** For Dependency Injection.
* **Play Asset Delivery:** The 4.6GB of audio is packaged via 5 asset pack modules (`bible_assets_1` through `5`) to circumvent the 4GB Zip32 limit of standard APKs.

## Development & Deployment
Due to the immense size of the audio assets (~4.6GB), standard ADB deployment takes approximately 5 minutes and may time out. 
To test rapidly, the `app/build.gradle.kts` uses a lightweight `bible_assets_test` module containing only the first 3 chapters of each book. For production releases, comment out the test pack and uncomment the full asset packs.
