package com.artillexstudios.axminions.executor

import com.artillexstudios.axapi.executor.ThreadedQueue
import com.artillexstudios.axminions.AxMinionsPlugin
import java.util.logging.Level

/**
 * Wraps axapi's [ThreadedQueue] for the database queue.
 *
 * axapi's `ThreadedQueue.run()` only catches [Exception], so a [Throwable] escaping a submitted
 * task (e.g. a [LinkageError] / [NoClassDefFoundError] from a broken integration) would kill the
 * queue thread permanently, silently stopping every future database write. axapi is a relocated,
 * external dependency we can't edit, so instead we defensively wrap every submitted task here to
 * catch [Throwable] before it can reach the library's loop. This is the equivalent of widening
 * that catch to [Throwable]: the worker thread can never die from a task failure.
 */
class DataQueue(private val name: String) {
    private val delegate = ThreadedQueue<Runnable>(name)

    fun submit(block: () -> Unit) {
        delegate.submit(Runnable {
            try {
                block()
            } catch (throwable: Throwable) {
                AxMinionsPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "[${Thread.currentThread().name}] Exception while executing a task on the $name; the queue thread has been kept alive",
                    throwable
                )
            }
        })
    }
}
