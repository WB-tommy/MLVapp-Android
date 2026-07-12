package fm.magiclantern.forum.features.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

object OutputDirectoryValidator {
    fun validationError(context: Context, uri: Uri): String? {
        return runCatching {
            val directory = DocumentFile.fromTreeUri(context, uri)
            when {
                directory == null ->
                    "Output folder is no longer available. Choose another folder."
                !directory.exists() ->
                    "Output folder no longer exists. Choose another folder."
                !directory.isDirectory ->
                    "Selected output location is not a folder. Choose another folder."
                !directory.canWrite() ->
                    "Output folder is not writable. Choose another folder."
                else -> null
            }
        }.getOrElse { throwable ->
            Log.e(TAG, "Unable to validate output directory: $uri", throwable)
            "Unable to access that folder. Choose another folder."
        }
    }

    private const val TAG = "fm.forum.mlv.OutputDirectoryValidator"
}
