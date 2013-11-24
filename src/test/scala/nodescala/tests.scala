package nodescala



import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be created") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }

  test("A Future should never be created") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("Future.all completes if all of the futures in the list have been completed") {
    val f1 = Future {1}
    val f2 = Future {2}
    val all = Future.all(List(f1, f2))

    assert(Await.result(all, 1 second) == List(1,2))
  }

  test("Future.all fails if any of the futures in the list cannot be completed") {
    val f1 = Future {1}
    val f2 = Future.never[Int]
    val all = Future.all(List(f1, f2))

    try {
      Await.result(all, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("Future.delay") {
    val delay = Future.delay(1 second)
    try {
      Await.result(delay, 2 second)
      assert(true)
    } catch {
      case t: TimeoutException => assert(false)
    }
  }

  test("Future.delay timeout") {
    val delay = Future.delay(2 second)
    try {
      Await.result(delay, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("Future.any") {
    val x = Promise[Int]();
    val f1 = future { x.tryComplete(Success(1)); 1}
    val f2 = future { x.tryComplete(Success(2)); 2}
    val f3 = future { x.tryComplete(Success(3)); 3}
    val any = Future.any(List(f1, f2, f3))
    val result = Await.result(any, 3 seconds)
    val xresult = Await.result(x.future, 3 seconds)
    assert(result == xresult)
  }

  test("Future.now") {
    val f = future {1}
    assert(f.now == 1)
  }

  ignore("Future.now non-complete") {
    val f = Future[Int] { while (true) { }; 3 }
    try {
      f.now
    } catch {
      case t: NoSuchElementException => // ok !
    }    
  }

  test("Future.now exception") {
    val f = Future[Int] { throw new IllegalStateException }
    f onComplete {
      case anyValue => try {
        f.now
        assert(false)
      } catch {
        case t: IllegalStateException => // ok !
      }
    }    
  }

  test("Future.continue") {
    val f1 = future{1}
    val f2 = f1 continue {
      case Success(s) => 2 * s
      case Failure(t) => 0
    }
    assert(Await.result(f2, 1 second) == 2)
  }

  test("Future.continue fail") {
    val f1 = Future[Int] {throw new IllegalStateException}
    val f2 = f1 continue {
      case Success(s) => 2 * s
      case Failure(t) => 0
    }
    assert(Await.result(f2, 1 second) == 0)
  }

  test("Future.continue exception") {
    val f1 = Future[Int] {0}
    val f2 = f1 continue {
      case anyValue => 2 / anyValue.get
    }
    try {
      Await.result(f2, 1 second)
      assert(false)
    } catch {
      case t: ArithmeticException => // ok!
    }
  }

  test("Future.continueWith") {
    val result = Future[String] {
      throw new IllegalStateException
    } continueWith { f => "continued" }
    assert(Await.result(result, 1 second) == "continued")
  }

  ignore("Promises") {
    val f1 = future { blocking{Thread.sleep(1000)}; println("f1"); 1}
    val f2 = future { blocking{Thread.sleep(20)}; println("f2"); 2}
    val f3 = future { blocking{Thread.sleep(2000)}; println("f3"); 3}
    val p = Promise[Int]()
    
    f1 onComplete { 
      case Success(e) => { println("f1 success=" + e); println("f1 completed=" + p.isCompleted); p.success(e); println("f1 end=" + p.isCompleted) }
      case Failure(e) => { println("f1 failure") }
      println("f1 outside")
      p.tryComplete(_) }
    f2 onComplete { 
      case Success(e) => { println("f2 success=" + e); println("f2 completed=" + p.isCompleted); p.success(e); println("f2 end=" + p.isCompleted) }
      case Failure(e) => { println("f2 failure")}
      println("f2 outside")
      p.tryComplete(_) }
    f3 onComplete { 
      case Success(e) => { println("f3 success=" + e); println("f3 completed=" + p.isCompleted); p.success(e); println("f3 end=" + p.isCompleted) }
      case Failure(e) => { println("f3 failure")}
      println("f3 outside")
      p.tryComplete(_) }

    f1 onSuccess { case e => println("f1 onsuccess") }
    f2 onSuccess { case e => println("f2 onsuccess") }
    f3 onSuccess { case e => println("f3 onsuccess") }
    val f = p.future
    assert(Await.result(f, 4 seconds) == 2)
  }

  test("CancellationTokenSource should allow stopping the computation") {
    val cts = CancellationTokenSource()
    val ct = cts.cancellationToken
    val p = Promise[String]()

    async {
      while (ct.nonCancelled) {
        // do work
      }

      p.success("done")
    }

    cts.unsubscribe()
    assert(Await.result(p.future, 1 second) == "done")
  }

  test("Future.run") {
    val p = Promise[String]()
    val working = Future.run() { ct =>
      Future {
        while (ct.nonCancelled) {
          // do work
        }
        p.success("done")
      }
    }
    Future.delay(5 seconds) onSuccess {
      case _ => working.unsubscribe()
    }
    assert(Await.result(p.future, 6 second) == "done")
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  ignore("Listener should serve the next request as a future") {
    val dummy = new DummyListener(8191, "/test")
    val subscription = dummy.start()

    def test(req: Request) {
      val f = dummy.nextRequest()
      dummy.emit(req)
      val (reqReturned, xchg) = Await.result(f, 1 second)

      assert(reqReturned == req)
    }

    test(immutable.Map("StrangeHeader" -> List("StrangeValue1")))
    test(immutable.Map("StrangeHeader" -> List("StrangeValue2")))

    subscription.unsubscribe()
  }

  ignore("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}




