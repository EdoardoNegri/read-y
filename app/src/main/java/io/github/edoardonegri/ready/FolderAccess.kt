package io.github.edoardonegri.ready

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks the reader for the library folder, Read_y: the only part of the phone's storage the app is
 * let into. Made with the activity [act], before it starts; [granted] is told whether the folder
 * picked became the library, and is not told anything if none was picked.
 */
class FolderAccess(private val act: ComponentActivity, private val granted: (Boolean) -> Unit) {

    private val pickFolder = act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        granted(Library.grant(act, uri))
    }

    fun request() {
        try {
            pickFolder.launch(Store.askIntent())
        } catch (e: Exception) {
            Toast.makeText(act, R.string.cannot_use_folder, Toast.LENGTH_SHORT).show()
        }
    }
}
