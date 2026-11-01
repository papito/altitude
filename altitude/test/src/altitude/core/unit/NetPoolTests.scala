package altitude.core.unit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.util.Try

import altitude.core.util.NetPool

/** The pool makes elements on demand up to its size, shares them one borrower at a time, and loses nothing to a throw */
@DoNotDiscover class NetPoolTests extends AnyFunSuite {

  /** Bounds every wait for something that must happen, so a broken pool fails a test instead of hanging the bundle */
  private val deadline = 30.seconds

  /** How long a blocked borrower is watched: a correct pool passes at any length, a longer watch only catches more broken ones */
  private val blockedWindow = 200.millis

  private class Element {
    val inUse = new AtomicBoolean(false)
  }

  private class CountingFactory extends (() => Element) {
    val created = new AtomicInteger(0)

    def apply(): Element = {
      created.incrementAndGet()
      new Element
    }
  }

  private def daemon(body: => Unit): Thread = {
    val thread = new Thread(() => body)
    thread.setDaemon(true)
    thread.start()
    thread
  }

  private def async[A](body: => A): Future[A] = {
    val promise = Promise[A]()
    daemon(promise.complete(Try(body)))
    promise.future
  }

  private def opens(latch: CountDownLatch, within: FiniteDuration): Boolean = latch.await(within.toMillis, TimeUnit.MILLISECONDS)

  /** A borrower on a thread of its own, keeping its element until told to give it back */
  private class Holder(pool: NetPool[Element]) {
    private val entered = new CountDownLatch(1)
    private val giveBackSignal = new CountDownLatch(1)
    @volatile private var held: Option[Element] = None

    private val done: Future[Unit] = async {
      pool.borrow {
        element =>
          held = Some(element)
          entered.countDown()
          giveBackSignal.await()
      }
    }

    def staysBlocked: Boolean = !opens(entered, blockedWindow)

    def element: Element = {
      opens(entered, deadline) shouldBe true
      held.get
    }

    def giveBack(): Unit = {
      giveBackSignal.countDown()
      Await.result(done, deadline)
    }
  }

  test("Nothing is made before the first borrow, and at most size elements after it") {

    /**
     * Setup:
     *
     * A pool of two over a counting factory. Two borrowers hold an element at once, give them back, then twenty borrows follow
     * one after another.
     *
     * Assertions:
     *
     * The factory is not called by building the pool. Two holders get two distinct elements. The later borrows make nothing and
     * all return what `use` returned.
     */
    val factory = new CountingFactory
    val pool = new NetPool[Element](2, factory)
    factory.created.get shouldEqual 0

    val holders = Seq(new Holder(pool), new Holder(pool))
    holders.map(_.element).distinct should have size 2
    factory.created.get shouldEqual 2
    holders.foreach(_.giveBack())

    (1 to 20).foreach(i => pool.borrow(_ => i) shouldEqual i)
    factory.created.get shouldEqual 2
  }

  test("A free element is handed back before another is made") {

    /**
     * Setup:
     *
     * A pool of three with room to make more.
     *
     * Assertions:
     *
     * Borrows one after another keep getting the one element made so far. With two made, one held and one returned, the next
     * borrower gets the returned one and the factory is not called.
     */
    val factory = new CountingFactory
    val pool = new NetPool[Element](3, factory)

    val first = pool.borrow(identity)
    pool.borrow(identity) should be theSameInstanceAs first
    factory.created.get shouldEqual 1

    val kept = new Holder(pool)
    val returned = new Holder(pool)
    val returnedElement = returned.element
    kept.element should not be theSameInstanceAs(returnedElement)
    returned.giveBack()

    val next = new Holder(pool)
    next.element should be theSameInstanceAs returnedElement
    factory.created.get shouldEqual 2

    kept.giveBack()
    next.giveBack()
  }

  test("The element goes back when use throws") {

    /**
     * Setup:
     *
     * A pool of one, whose `use` throws on the first borrow.
     *
     * Assertions:
     *
     * The exception reaches the caller unchanged. The next borrow, bounded so that a lost permit fails rather than hangs, gets
     * the same element, and the factory has been called once.
     */
    val factory = new CountingFactory
    val pool = new NetPool[Element](1, factory)
    val failure = new IllegalStateException("forward pass failed")
    val elementSeen = Promise[Element]()

    val thrown = intercept[IllegalStateException] {
      pool.borrow {
        element =>
          elementSeen.success(element)
          throw failure
      }
    }
    thrown should be theSameInstanceAs failure

    Await.result(async(pool.borrow(identity)), deadline) should be theSameInstanceAs Await.result(elementSeen.future, deadline)
    factory.created.get shouldEqual 1
  }

  test("A third borrower waits until one of two elements is returned") {

    /**
     * Setup:
     *
     * A pool of two with both elements held, and a third borrower.
     *
     * Assertions:
     *
     * The third does not enter `use` while both are out. When one comes back it gets that one, and nothing new is made. The other
     * holder is still holding at that point.
     */
    val factory = new CountingFactory
    val pool = new NetPool[Element](2, factory)
    val first = new Holder(pool)
    val second = new Holder(pool)
    val firstElement = first.element
    second.element

    val third = new Holder(pool)
    third.staysBlocked shouldBe true

    first.giveBack()
    third.element should be theSameInstanceAs firstElement
    factory.created.get shouldEqual 2

    second.giveBack()
    third.giveBack()
  }

  test("A factory that throws loses no capacity") {

    /**
     * Setup:
     *
     * A pool of one whose factory throws on its first call and works from then on.
     *
     * Assertions:
     *
     * The factory's exception reaches the caller and `use` does not run. The next borrow, bounded so that a lost permit fails
     * rather than hangs, makes an element.
     */
    val attempts = new AtomicInteger(0)
    val pool = new NetPool[Element](
      1,
      () => if attempts.incrementAndGet() == 1 then throw new IllegalStateException("model missing") else new Element
    )

    intercept[IllegalStateException](pool.borrow(_ => fail("use ran without an element")))

    Await.result(async(pool.borrow(_ => "used")), deadline) shouldEqual "used"
    attempts.get shouldEqual 2
  }

  test("A borrower interrupted while waiting gives back nothing it did not take") {

    /**
     * Setup:
     *
     * A pool of one with its element held, a waiting borrower that gets interrupted, then another borrower.
     *
     * Assertions:
     *
     * The interrupted borrower gets an InterruptedException. The element is still out, so the next borrower still waits: a permit
     * released by the interrupted one would let it through to make a second element. It proceeds on the real return.
     */
    val factory = new CountingFactory
    val pool = new NetPool[Element](1, factory)
    val holder = new Holder(pool)
    val element = holder.element

    val interrupted = new CountDownLatch(1)
    val waiter = daemon {
      try pool.borrow(_ => fail("use ran while the only element was out"))
      catch case _: InterruptedException => interrupted.countDown()
    }
    waiter.interrupt()
    opens(interrupted, deadline) shouldBe true

    val next = new Holder(pool)
    next.staysBlocked shouldBe true

    holder.giveBack()
    next.element should be theSameInstanceAs element
    factory.created.get shouldEqual 1
    next.giveBack()
  }

  test("Under contention no element is shared and no more than size are made") {

    /**
     * Setup:
     *
     * Eight threads released together, each borrowing five hundred times from a pool of two.
     *
     * Assertions:
     *
     * No `use` ever finds its element in use by another. The factory is called at most twice, and every thread finishes.
     *
     * Edge cases:
     *
     * Elements returning while others look for a free one, the window in which a queue read empty could lead to a third element
     * if the permit were released before the element went back.
     */
    val factory = new CountingFactory
    val pool = new NetPool[Element](2, factory)
    val start = new CountDownLatch(1)

    val workers = (1 to 8).map {
      _ =>
        async {
          start.await()
          (1 to 500).foreach {
            _ =>
              pool.borrow {
                element =>
                  element.inUse.compareAndSet(false, true) shouldBe true
                  Thread.`yield`()
                  element.inUse.set(false)
              }
          }
        }
    }
    start.countDown()

    workers.foreach(Await.result(_, deadline))
    factory.created.get should be <= 2
  }

  test("A size under one is refused") {

    /**
     * Assertions:
     *
     * Zero and a negative size throw when the pool is built: such a pool could only ever block.
     */
    Seq(0, -1).foreach {
      size =>
        withClue(s"size $size: ") {
          intercept[IllegalArgumentException](new NetPool[Element](size, new CountingFactory))
        }
    }
  }
}
