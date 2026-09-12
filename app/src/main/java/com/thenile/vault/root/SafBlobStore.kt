package com.thenile.vault.root

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/** Storage Access Framework helper so SoftVault can park encrypted blobs under a directory the
 *  user picked (via ACTION_OPEN_DOCUMENT_TREE) instead of only Nile's private storage. Hand-rolled
 *  against DocumentsContract rather than pulling in androidx.documentfile for what's really just
 *  "find/create/delete a child document by name" — the platform API already covers it directly. */
object SafBlobStore {
    private const val TAG = "SafBlobStore"
    private const val MIME = "application/octet-stream"

    private fun rootDocUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun findChild(context: Context, treeUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(rootDocUri(treeUri)))
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    fun write(context: Context, treeUri: Uri, name: String, source: File): Boolean = try {
        val docUri = findChild(context, treeUri, name)
            ?: DocumentsContract.createDocument(context.contentResolver, rootDocUri(treeUri), MIME, name)
        if (docUri == null) {
            false
        } else {
            context.contentResolver.openOutputStream(docUri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "write failed for $name: ${e.message}", e)
        false
    }

    fun read(context: Context, treeUri: Uri, name: String, dest: File): Boolean = try {
        val docUri = findChild(context, treeUri, name)
        if (docUri == null) {
            false
        } else {
            context.contentResolver.openInputStream(docUri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.exists()
        }
    } catch (e: Exception) {
        Log.e(TAG, "read failed for $name: ${e.message}", e)
        false
    }

    fun delete(context: Context, treeUri: Uri, name: String) {
        try {
            findChild(context, treeUri, name)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
        } catch (e: Exception) {
            Log.w(TAG, "delete failed for $name: ${e.message}")
        }
    }
}
