package altitude.core.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/**
 * A bounded pool of elements too costly to give each thread its own, made on demand up to `size` and kept for the pool's life.
 * Borrowers wait in arrival order. A `use` that borrows from the same pool again can deadlock against itself, so it must not.
 *
 * The permits are the only count: an element goes back on the queue before its permit is released, so a permit holder that finds
 * the queue empty knows the other holders have every element, at most `size - 1`, and may make one more.
 */
class NetPool[T](size: Int, factory: () => T):
  require(size > 0, s"A pool needs at least one element, got size $size")

  private val permits = new Semaphore(size, true)
  private val free = new ConcurrentLinkedQueue[T]()

  /** The element goes back however `use` ends */
  def borrow[A](use: T => A): A =
    // outside the try: an interrupted acquire has taken no permit, so it must release none
    permits.acquire()
    try
      val element = Option(free.poll()).getOrElse(factory())
      try use(element)
      finally free.add(element)
    finally permits.release()
