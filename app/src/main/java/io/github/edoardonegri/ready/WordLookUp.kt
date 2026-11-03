package io.github.edoardonegri.ready

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.net.toUri

/**
 * The apps of the phone a word held in the reader can be looked up in: those that look up words
 * selected in any app, a dictionary or a translator (ACTION_PROCESS_TEXT), and those that search
 * the web for it, the browsers and the phone's search app. The reader itself stays offline. Those
 * offered are the ones chosen in the settings (ReaderSettings.lookUpApps); until any are, the
 * phone's own search engine alone ([chosen]).
 */
class WordLookUp(
    /** What it is told apart by in the settings, and kept under while it is chosen. */
    val key: String,
    /** The app it is of. */
    val pkg: String,
    val label: CharSequence,
    /** Its picture, loaded only when it is shown: of all the apps, most are not. */
    loadIcon: () -> Drawable,
    /** What opens it on a word. */
    private val open: (String) -> Intent,
) {
    val icon: Drawable by lazy(loadIcon)

    fun intent(word: String) = open(word)

    /** A picture of it of its own, for a view to show, or to tint, while others show [icon] too. */
    fun picture(): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

    /** Whether it searches the web for the word, rather than looking it up itself. */
    val searchesWeb get() = key.startsWith(WEB)

    companion object {
        private const val WEB = "web:"

        /** Every app of the phone a word can be looked up in: those that look up words first, then those that search the web, by name. */
        fun all(ctx: Context): List<WordLookUp> {
            val pm = ctx.packageManager
            val words = pm.queryIntentActivities(processText(""), 0).map { it.activityInfo }.map { a ->
                WordLookUp("text:${a.packageName}/${a.name}", a.packageName, a.loadLabel(pm), { a.loadIcon(pm) }) { word ->
                    processText(word).setClassName(a.packageName, a.name)
                }
            }.sortedBy { it.label.toString().lowercase() }
            val searching = (pm.queryIntentActivities(Intent(Intent.ACTION_WEB_SEARCH), 0) + pm.queryIntentActivities(browsing(), PackageManager.MATCH_ALL))
                .map { it.activityInfo }.distinctBy { it.packageName }.map { a ->
                    val app = a.applicationInfo
                    WordLookUp("$WEB${a.packageName}", a.packageName, app.loadLabel(pm), { app.loadIcon(pm) }) { word -> search(pm, a.packageName, word) }
                }.sortedBy { it.label.toString().lowercase() }
            return words + searching
        }

        /** Those of [all] chosen in the settings [s], in the order they were chosen. */
        fun chosen(ctx: Context, s: ReaderSettings, all: List<WordLookUp> = all(ctx)): List<WordLookUp> {
            val keys = s.lookUpApps ?: return listOfNotNull(phoneSearch(ctx, all))
            return keys.mapNotNull { key -> all.firstOrNull { it.key == key } }
        }

        /** The keys [chosen] in the settings [s] stand for, which a change to them starts from. */
        fun chosenKeys(ctx: Context, s: ReaderSettings, all: List<WordLookUp>) = chosen(ctx, s, all).map { it.key }

        /**
         * The phone's own search engine, of [all]: the app it was told to search the web with; else
         * the one it searches with itself, from the home screen, such as the Google app; else the
         * browser it opens pages in; else the first that searches the web.
         */
        private fun phoneSearch(ctx: Context, all: List<WordLookUp>): WordLookUp? {
            val pm = ctx.packageManager
            val web = all.filter { it.searchesWeb }
            fun of(pkg: String?) = pkg?.let { p -> web.firstOrNull { it.pkg == p } }
            // Asked for an app the phone has no default for, it names its own chooser.
            fun default(intent: Intent) = of(pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" })
            val own = ctx.getSystemService(SearchManager::class.java)?.globalSearchActivity?.packageName
            return default(Intent(Intent.ACTION_WEB_SEARCH)) ?: of(own) ?: default(browsing()) ?: web.firstOrNull()
        }

        private fun browsing() = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

        private fun processText(word: String) = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

        /**
         * Searches the web for [word] in the app [pkg]: with the search engine it is set to, where
         * it takes a search, or else with a page of results.
         */
        private fun search(pm: PackageManager, pkg: String, word: String): Intent {
            val ask = Intent(Intent.ACTION_WEB_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, word)
            if (pm.resolveActivity(ask, 0) != null) return ask
            return Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=${Uri.encode(word)}".toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        }
    }
}
