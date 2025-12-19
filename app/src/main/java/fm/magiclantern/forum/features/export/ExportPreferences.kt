package fm.magiclantern.forum.features.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPORT_PREFS = "export_prefs"
private const val KEY_OUTPUT_URI = "output_uri"

@Singleton
class ExportPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE)

    fun getLastOutputDirectory(): Uri? =
        prefs.getString(KEY_OUTPUT_URI, null)?.let(Uri::parse)

    fun setLastOutputDirectory(uri: Uri) {
        prefs.edit().putString(KEY_OUTPUT_URI, uri.toString()).apply()
    }

    fun clearOutputDirectory() {
        prefs.edit().remove(KEY_OUTPUT_URI).apply()
    }
}
