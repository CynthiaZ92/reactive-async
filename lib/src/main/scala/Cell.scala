package cell

import java.util.concurrent.atomic._
import java.util.concurrent.{ExecutionException, CountDownLatch}

import scala.annotation.tailrec

import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

import scala.concurrent.{ExecutionContext, OnCompleteRunnable}


/**
 * Example:
 *
 *   val barRetTypeCell: Cell[(Entity, PropertyKind), ObjectType]
 */
trait Cell[K, V] {
  def key: K
  // def property: V

  def dependencies: Seq[K]
  // def addDependency(other: K)
  // def removeDependency(other: K)

  // sobald sich der Wert dieser Cell ändert, müssen die dependee Cells benachrichtigt werden
  // def dependees: Seq[K]
  // def addDependee(k: K): Unit
  // def removeDependee(k: K): Unit

  /**
   * Adds a dependency on some `other` cell.
   *
   * Example:
   *   whenComplete(cell, x => !x, Impure) // if `cell` is completed and the predicate is true (meaning
   *                                       // `cell` is impure), `this` cell can be completed with constant `Impure`
   *
   * @param other  Cell that `this` Cell depends on.
   * @param pred   Predicate used to decide whether a final result of `this` Cell can be computed early.
   *               `pred` is applied to value of `other` cell.
   * @param value  Early result value.
   */
  def whenComplete(other: Cell[K, V], pred: V => Boolean, value: V)(implicit context: ExecutionContext): Unit

  /**
   * Registers a call-back function to be invoked when quiescence is reached, but `this` cell has not been
   * completed, yet. The call-back function is passed a sequence of the cells that `this` cell depends on.
   */
  // def onCycle(callback: Seq[Cell[K, V]] => V)

  // internal API

  // Schedules execution of `callback` when next intermediate result is available.
  // def onNext[U](callback: V => U)(implicit context: ExecutionContext): Unit

  // Schedules execution of `callback` when completed with final result.
  def onComplete[U](callback: Try[V] => U)(implicit context: ExecutionContext): Unit

  def waitUntilNoDeps(): Unit
}


/**
 * Interface trait for programmatically completing a cell. Analogous to `Promise`.
 */
trait CellCompleter[K, V] {
  def cell: Cell[K, V]

  def putFinal(x: V): Unit
  def putNext(x: V): Unit

  def tryComplete(value: Try[V]): Boolean

  private[cell] def removeDep(dep: DepRunnable[K, V]): Unit
}

object CellCompleter {
  def apply[K, V](key: K): CellCompleter[K, V] =
    new CellImpl[K, V](key)
}


/* Depend on `cell`. `pred` to decide whether short-cutting is possible. `value` is short-cut result.
 */
class Dep[K, V](val cell: Cell[K, V], val pred: V => Boolean, val value: V)


/* State of a cell that is not yet completed.
 *
 * This is not a case class, since it is important that equality is by-reference.
 *
 * @param res       current intermediate result (optional)
 * @param deps      dependencies on other cells
 * @param callbacks list of registered call-back runnables
 */
private class State[K, V](val res: Option[V], val deps: List[DepRunnable[K,V]], val callbacks: List[CallbackRunnable[V]])

private object State {
  def empty[K, V]: State[K, V] =
    new State[K, V](None, List(), List())
}


class CellImpl[K, V](val key: K) extends Cell[K, V] with CellCompleter[K, V] {

  private val nodepslatch = new CountDownLatch(1)

  /* Contains a value either of type
   * (a) `Try[V]`      for the final result, or
   * (b) `State[K,V]`  for an incomplete state.
   *
   * Assumes that dependencies need to be kept until a final result is known.
   */
  private val state = new AtomicReference[AnyRef](State.empty[K, V])

  // `CellCompleter` and corresponding `Cell` are the same run-time object.
  override def cell: Cell[K, V] = this

  override def putFinal(x: V): Unit = {
    val res = tryComplete(Success(x))
    if (!res) throw new IllegalStateException("Cell already completed.")
  }

  override def putNext(x: V): Unit = ???

  override def dependencies: Seq[K] = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        Seq[K]()
      case pre: State[_, _] => // not completed
        val current = pre.asInstanceOf[State[K, V]]
        current.deps.map(_.cell.key)
    }
  }

  /** Adds dependency on `other` cell: when `other` cell is completed, evaluate `pred`
   *  with the result of `other`. If this evaluation yields true, complete `this` cell
   *  with `value`.
   *
   *  The thereby introduced dependency is removed when `this` cell
   *  is completed (either prior or after an invocation of `whenComplete`).
   *
   *  TODO: distinguish final result from other results.
   */
  override def whenComplete(other: Cell[K, V], pred: V => Boolean, value: V)(implicit context: ExecutionContext): Unit = {
    state.get() match {
      case finalRes: Try[_]  => // completed with final result
        // do not add dependency
        // in fact, do nothing

      case raw: State[_, _] => // not completed
        val newDep = new DepRunnable(context, other, pred, value, this)
        other.onComplete(newDep)

        val current  = raw.asInstanceOf[State[K, V]]
        val newState = new State(current.res, newDep :: current.deps, current.callbacks)
        state.compareAndSet(current, newState)
    }
  }

  /** Called by `tryComplete` to store the resolved value and get the current state
   *  or `null` if it is already completed.
   */
  // TODO: take care of compressing root (as in impl.Promise.DefaultPromise)
  @tailrec
  private def tryCompleteAndGetState(v: Try[V]): State[K, V] = {
    state.get() match {
      case current: State[_, _] =>
        if (state.compareAndSet(current, v))
          current.asInstanceOf[State[K, V]]
        else
          tryCompleteAndGetState(v)

      case _ => null
    }
  }

  override def tryComplete(value: Try[V]): Boolean = {
    val resolved = resolveTry(value)
    tryCompleteAndGetState(resolved) match {
      case null                                      => false // was already complete
      case pre: State[_, _] if pre.callbacks.isEmpty => true
      case pre: State[k, v]                          =>
        pre.callbacks.foreach(r => r.executeWithValue(resolved))
        true
    }
  }

  @tailrec
  override private[cell] final def removeDep(dep: DepRunnable[K, V]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newDeps = current.deps.filterNot(_ == dep)

        if (newDeps.isEmpty)
          nodepslatch.countDown()

        val newState = new State(current.res, newDeps, current.callbacks)
        if (!state.compareAndSet(current, newState))
          removeDep(dep)

      case _ => /* do nothing */
    }
  }

  def waitUntilNoDeps(): Unit = {
    nodepslatch.await()
  }

  // Schedules execution of `callback` when completed with final result.
  override def onComplete[U](callback: Try[V] => U)(implicit context: ExecutionContext): Unit = {
    val runnable = new CallbackRunnable[V](context, callback)
    dispatchOrAddCallback(runnable)
  }

  /** Tries to add the callback, if already completed, it dispatches the callback to be executed.
   *  Used by `onComplete()` to add callbacks to a promise and by `link()` to transfer callbacks
   *  to the root promise when linking two promises together.
   */
  @tailrec
  private def dispatchOrAddCallback(runnable: CallbackRunnable[V]): Unit = {
    state.get() match {
      case r: Try[_]  => runnable.executeWithValue(r.asInstanceOf[Try[V]])
      // case _: DefaultPromise[_] => compressedRoot().dispatchOrAddCallback(runnable)
      case pre: State[_, _] =>
        // assemble new state
        val current  = pre.asInstanceOf[State[K, V]]
        val newState = new State(current.res, current.deps, runnable :: current.callbacks)
        if (!state.compareAndSet(pre, newState)) dispatchOrAddCallback(runnable)
    }
  }

  // copied from object `impl.Promise`
  private def resolveTry[T](source: Try[T]): Try[T] = source match {
    case Failure(t) => resolver(t)
    case _          => source
  }

  // copied from object `impl.Promise`
  private def resolver[T](throwable: Throwable): Try[T] = throwable match {
    case t: scala.runtime.NonLocalReturnControl[_] => Success(t.value.asInstanceOf[T])
    case t: scala.util.control.ControlThrowable    => Failure(new ExecutionException("Boxed ControlThrowable", t))
    case t: InterruptedException                   => Failure(new ExecutionException("Boxed InterruptedException", t))
    case e: Error                                  => Failure(new ExecutionException("Boxed Error", e))
    case t                                         => Failure(t)
  }

}

/* Depend on `cell`. `pred` to decide whether short-cutting is possible. `shortCutValue` is short-cut result.
 */
private class DepRunnable[K, V](val executor: ExecutionContext,
                                val cell: Cell[K, V],
                                val pred: V => Boolean,
                                val shortCutValue: V,
                                val completer: CellCompleter[K, V])
    extends Runnable with OnCompleteRunnable with (Try[V] => Unit) {
  // must be filled in before running it
  var value: Try[V] = null

  override def apply(x: Try[V]): Unit = x match {
    case Success(v) =>
      if (pred(v)) completer.tryComplete(Success(shortCutValue))
      else completer.removeDep(this)
    case Failure(e) =>
      completer.removeDep(this)
  }

  override def run(): Unit = {
    try apply(value) catch { case NonFatal(e) => executor reportFailure e }
  }

  def executeWithValue(v: Try[V]): Unit = {
    value = v
    try executor.execute(this) catch { case NonFatal(t) => executor reportFailure t }
  }
}


// copied from `impl.CallbackRunnable` in Scala core lib.
private class CallbackRunnable[T](val executor: ExecutionContext, val onComplete: Try[T] => Any) extends Runnable with OnCompleteRunnable {
  // must be filled in before running it
  var value: Try[T] = null

  override def run() = {
    require(value ne null) // must set value to non-null before running!
    try onComplete(value) catch { case NonFatal(e) => executor reportFailure e }
  }

  def executeWithValue(v: Try[T]): Unit = {
    require(value eq null) // can't complete it twice
    value = v
    // Note that we cannot prepare the ExecutionContext at this point, since we might
    // already be running on a different thread!
    try executor.execute(this) catch { case NonFatal(t) => executor reportFailure t }
  }
}
