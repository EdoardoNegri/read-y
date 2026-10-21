package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Who made Read-y, and how to reach or thank them: a dialog of the bookshelf's menu, whose arrow
 * leads [back] to it.
 */
class AboutDialog(private val act: Activity, private val back: () -> Unit) {

    fun show() {
        val version = try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
        ShelfDialog(act).title(act.getString(R.string.about)).back(back)
            .note(act.getString(R.string.about_hi))
            .note(act.getString(R.string.about_feedback_ask))
            .button(act.getString(R.string.about_contact)) { writeMail(version) }
            .note(act.getString(R.string.about_rate_ask))
            .button(act.getString(R.string.about_rate), ::rateApp)
            .apply {
                // Until there is a page to be thanked on, there is no button for it.
                if (SUPPORT_URL.isNotEmpty()) note(act.getString(R.string.about_support_ask)).button(act.getString(R.string.about_support)) { openLink(SUPPORT_URL) }
            }
            .show()
    }

    /** A mail to the author, which says by itself which app, which version and which phone it comes from. */
    private fun writeMail(version: String) {
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT))
            putExtra(Intent.EXTRA_SUBJECT, act.getString(R.string.mail_subject, version))
            putExtra(
                Intent.EXTRA_TEXT,
                "\n\n--\nRead-y $version\n" +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n",
            )
        }
        try {
            act.startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.about_no_mail)
        }
    }

    /** Opens the app's page in the Play Store, where it is rated; in the browser if the phone has no Play Store. */
    private fun rateApp() {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${act.packageName}".toUri()))
        } catch (e: Exception) {
            openLink("https://play.google.com/store/apps/details?id=${act.packageName}")
        }
    }

    private fun openLink(url: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            toast(R.string.about_no_browser)
        }
    }

    private fun toast(text: Int) = Toast.makeText(act, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val CONTACT = "edon.negri@gmail.com"

        /** Where the author can be thanked with a drink, e.g. "https://ko-fi.com/<name>"; empty hides the button. */
        const val SUPPORT_URL = "https://revolut.me/edo_ne"
    }
}
