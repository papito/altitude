package altitude.core.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/**
 * A bounded pool of elements too costly to give each thread its own: they are made on demand, up to `size`, and kept for the life
 * of the pool. A borrower waits, in arrival order, while all `size` are out. A `use` that borrows from the same pool again can
 * deadlock against itself, so it must not.
 *
 * The permits are the only count. Each borrower holds one permit and at most one element, and an element goes back on the queue
 * before its permit is released, so every element is either on the queue or held by a permit holder. A borrower holding a permit
 * that finds the queue empty therefore knows the elements in existence are all with the other holders, at most `size - 1` of
 * them, and it may make one more.
 */
class NetPool[T](size: Int, factory: () => T):
  require(size > 0, s"A pool needs at least one element, got size $size")

  private val permits = new Semaphore(size, true)
  private val free = new ConcurrentLinkedQueue[T]()

  /** Runs `use` with an element to itself, waiting for one if need be. The element goes back however `use` ends. */
  def borrow[A](use: T => A): A =
    // outside the try: an interrupted acquire has taken no permit, so it must release none
    permits.acquire()
    try
      val element = Option(free.poll()).getOrElse(factory())
      try use(element)
      finally free.add(element)
    finally permits.release()
