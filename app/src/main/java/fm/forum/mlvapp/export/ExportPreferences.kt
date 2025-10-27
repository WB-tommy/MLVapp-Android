package fm.forum.mlvapp.export

import android.content.Context
import android.net.Uri

private const val EXPORT_PREFS = "export_prefs"
private const val KEY_OUTPUT_URI = "output_uri"

class ExportPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE)

    fun getLastOutputDirectory(): Uri? =
        prefs.getString(KEY_OUTPUT_URI, null)?.let(Uri::parse)

    fun setLastOutputDirectory(uri: Uri) {
        prefs.edit().putString(KEY_OUTPUT_URI, uri.toString()).apply()
    }

    fun clearOutputDirectory() {
        prefs.edit().remove(KEY_OUTPUT_URI).apply()
    }
}
