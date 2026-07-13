package com.sameerakhtari.riddle.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.LocalModelSpec
import com.sameerakhtari.riddle.logging.AppLog
import java.io.File

object ModelLibraryStorage {
    fun selectedTreeUri(context: Context): Uri? {
        val value = AppSettings(context).modelLibraryTreeUri
        return value.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    fun persistTree(context: Context, uri: Uri, grantedFlags: Int = 0) {
        val requested = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val flags = (grantedFlags and requested).takeIf { it != 0 } ?: requested
        context.contentResolver.takePersistableUriPermission(uri, flags)
        AppSettings(context).modelLibraryTreeUri = uri.toString()
        AppLog.i("ModelLibrary", "Selected visible model folder: $uri")
    }

    fun folderPickerIntent(current: Uri? = null): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            if (current != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, current)
        }

    fun openFolderIntent(context: Context): Intent? {
        val tree = selectedTreeUri(context) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(tree, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun copyToVisibleLibrary(context: Context, spec: LocalModelSpec, source: File) {
        val tree = selectedTreeUri(context) ?: return
        val directory = DocumentFile.fromTreeUri(context, tree)
            ?: error("The selected model folder is no longer available.")
        require(directory.canWrite()) { "The selected model folder is read-only." }

        directory.findFile(spec.fileName)?.delete()
        val target = directory.createFile("application/octet-stream", spec.fileName)
            ?: error("Could not create ${spec.fileName} in the selected folder.")
        context.contentResolver.openOutputStream(target.uri, "w").use { output ->
            requireNotNull(output) { "Could not open the selected model folder for writing." }
            source.inputStream().buffered(1024 * 1024).use { input -> input.copyTo(output, 1024 * 1024) }
        }
        AppLog.i("ModelLibrary", "Copied ${spec.fileName} to the visible model folder")
    }
}
