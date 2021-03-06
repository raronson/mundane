package com.ambiata.mundane.control

import scala.util.control.NonFatal
import scalaz._, Scalaz._, \&/._
import scalaz.concurrent.Task
import scalaz.effect._
import scala.collection.JavaConverters._

case class RIO[A](private val exec: java.util.concurrent.ConcurrentLinkedQueue[Finalizer] => ResultT[IO, A]) {
  def unsafePerformIO: Result[A] =
    unsafeIO.unsafePerformIO

  /* Not safe until the end of the world */
  def unsafeIO: IO[Result[A]] = {
    val x = new java.util.concurrent.ConcurrentLinkedQueue[Finalizer]
    liftExceptions.ensuring(RIO.unsafeFlushFinalizers).exec(x).run
  }

  def isOk: RIO[Boolean] =
    RIO(f => RIO.fromIO(exec(f).isOk).exec(f))

  def liftExceptions: RIO[A] =
    RIO(f => {
      ResultT(exec(f).run.catchLeft.flatMap({
        case -\/(t) => RIO.exception[A](t).exec(f).run
        case \/-(s) => ResultT.result[IO, A](s).run
      }))
    })

  def bracket[B, C](after: Result[A] => RIO[B])(during: Result[A] => RIO[C]): RIO[C] =
    RIO(f => ResultT(exec(f).run.bracket(a => after(a).exec(f).run)(a => during(a).exec(f).run)))

  def ensuring[B](sequel: RIO[B]): RIO[A] =
    RIO(f =>
      ResultT(exec(f).run.ensuring(sequel.exec(f).run))
    )

  def on[X](f: Result[A] => RIO[X]): RIO[X] =
    RIO(finalizers => {
      val iora = liftExceptions.exec(finalizers).run
      ResultT(iora.flatMap(ra => f(ra).exec(finalizers).run))
    })

  def onError(f: These[String, Throwable] => RIO[A]): RIO[A] =
    on({
      case o @ Ok(_) =>
        RIO.result(o)
      case Error(e) =>
        f(e)
    })

  def map[B](f: A => B): RIO[B] =
    RIO(finalizers => exec(finalizers).map(f))

  def flatMap[B](f: A => RIO[B]): RIO[B] =
    RIO(finalizers => exec(finalizers).flatMap(a => f(a).exec(finalizers)))

  def flatMapError(f: These[String, Throwable] => RIO[A]): RIO[A] =
    RIO(finalizers => exec(finalizers).flatMapError(e => f(e).exec(finalizers)))

  def onResult[B](f: Result[A] => Result[B]): RIO[B] =
    RIO(finalizers => exec(finalizers).onResult(f))

  def mapError(f: These[String, Throwable] => These[String, Throwable]): RIO[A] =
    onResult(_.mapError(f))

  def |||(otherwise: => RIO[A]): RIO[A] =
    RIO[A](finalizers => exec(finalizers).|||(otherwise.exec(finalizers)))

  def orElse(otherwise: => A): RIO[A] =
     |||(RIO.ok(otherwise))

  def zip[B](other: RIO[B]): RIO[(A, B)] =
    flatMap(a => other.map(a -> _))
}

object RIO {
  def addFinalizer(f: Finalizer): RIO[Unit] =
    RIO(finalizers => { finalizers.add(f); ResultT.unit })

  def addResourceFinalizer[A: Resource, B <: A](r: B): RIO[Unit] =
    addFinalizer(Finalizer(RIO.fromIO(implicitly[Resource[A]].close(r))))

  def unsafeFlushFinalizers: RIO[Unit] = RIO(x => {
    ResultT.safe[IO, Unit](x.asScala.toList.foreach(_.run.unsafePerformIO match {
      case Ok(_) => ()
      case Error(e) => e match {
        case This(s) => throw new RuntimeException(s)
        case That(t) => throw new RuntimeException(t)
        case Both(s, t) => throw new RuntimeException(s, t)
      }
    }))
  })

  def safe[A](thunk: => A): RIO[A] =
    RIO[A](_ => ResultT.safe(thunk))

  def io[A](thunk: => A): RIO[A] =
    fromIO { IO { thunk } }

  def option[A](thunk: => A): RIO[Option[A]] =
    RIO[Option[A]](_ => ResultT.option(thunk))

  def ok[A](value: A): RIO[A] =
    RIO[A](_ => ResultT.ok(value))

  def unit: RIO[Unit] =
    RIO[Unit](_ => ResultT.ok(()))

  def result[A](result: Result[A]): RIO[A] =
    RIO[A](_ => ResultT.result(result))

  def resultT[A](result: ResultT[IO, A]): RIO[A] =
    RIO[A](_ => result)

  def exception[A](t: Throwable): RIO[A] =
    these[A](That(t))

  def fail[A](message: String): RIO[A] =
    these[A](This(message))

  def failIO[A](message: String): RIO[A] =
    fail[A](message)

  def putStrLn(x: String): RIO[Unit] =
    fromIO(IO.putStrLn(x))

  def error[A](message: String, t: Throwable): RIO[A] =
    these[A](Both(message, t))

  def these[A](both: These[String, Throwable]): RIO[A] =
    RIO[A](_ => ResultT.these(both))

  def fromDisjunction[A](v: These[String, Throwable] \/ A): RIO[A] =
    RIO[A](_ => ResultT.fromDisjunction(v))

  def fromIO[A](v: IO[A]): RIO[A] =
    RIO[A](_ => ResultT(v.map(Result.ok)))

  def fromDisjunctionString[A](v: String \/ A): RIO[A] =
    fromDisjunction(v.leftMap(This.apply))

  def fromDisjunctionThrowable[A](v: Throwable \/ A): RIO[A] =
    fromDisjunction(v.leftMap(That.apply))

  def fromOption[A](v: Option[A], failure: String): RIO[A] =
    v.cata(ok[A], fail(failure))

  def when(v: Boolean, thunk: => RIO[Unit]): RIO[Unit] =
    if (v) thunk else unit

  def unless(v: Boolean, thunk: => RIO[Unit]): RIO[Unit] =
    when(!v, thunk)

  def using[A: Resource, B <: A, C](a: RIO[B])(run: B => RIO[C]): RIO[C] =
    a.bracket((aa: Result[B]) => aa match {
      case Error(e) => RIO.unit
      case Ok(aaa) => RIO.fromIO(implicitly[Resource[A]].close(aaa))
    })((aa: Result[B]) => aa match {
      case Error(e) => RIO.these[C](e)
      case Ok(aaa) => run(aaa)
    })

  implicit def RIOMonad: MonadIO[({ type l[a] = RIO[a] })#l] = {
    type ResultTIO[A] = ResultT[IO, A]
    new MonadIO[({ type l[a] = RIO[a] })#l] {
      def point[A](v: => A) = ok[A](v)
      def bind[A, B](m: RIO[A])(f: A => RIO[B]) = m.flatMap(f)
      def liftIO[A](ioa: IO[A]): RIO[A] = RIO(_ => ioa.liftIO[ResultTIO])
    }
  }

  def toTask[A](result: =>RIO[A]): Task[A] =
    Task.delay {
      result.unsafePerformIO.foldAll(
        a => Task.delay(a),
        m => Task.fail(new Exception(m)),
        Task.fail,
        (m, e) => Task.fail(new Exception(m, e))
      )
    }.flatMap(identity)
}

case class Finalizer(run: RIO[Unit])
