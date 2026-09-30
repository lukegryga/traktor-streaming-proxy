package app

import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Two instances both bind port 443 and, worse, both run the Spotify OAuth callback on 5588, so a
 * login completes against whichever happens to be listening and fails the PKCE check. Likely once
 * start-at-login is on and the user also launches by hand.
 */
object SingleInstance {

    private var lock: FileLock? = null
    private var file: RandomAccessFile? = null

    fun acquire(): Boolean {
        val target = AppPaths.data("app.lock")
        return try {
            val handle = RandomAccessFile(target, "rw")
            val acquired = handle.channel.tryLock()
            if (acquired == null) {
                handle.close()
                false
            } else {
                // Held for the process lifetime; released by the OS on exit, including a kill.
                file = handle
                lock = acquired
                true
            }
        } catch (ex: Exception) {
            // A lock failure must not stop a normal start, so assume no other instance.
            true
        }
    }
}
