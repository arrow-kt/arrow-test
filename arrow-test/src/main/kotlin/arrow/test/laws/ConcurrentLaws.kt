package arrow.test.laws

import arrow.Kind
import arrow.core.Either
import arrow.core.Right
import arrow.core.Tuple2
import arrow.core.identity
import arrow.effects.CancelToken
import arrow.effects.MVar
import arrow.effects.Promise
import arrow.effects.Semaphore
import arrow.effects.typeclasses.Concurrent
import arrow.effects.typeclasses.ExitCase
import arrow.test.generators.applicativeError
import arrow.test.generators.either
import arrow.test.generators.throwable
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

@Suppress("LargeClass")
object ConcurrentLaws {

  fun <F> laws(
    CF: Concurrent<F>,
    EQ: Eq<Kind<F, Int>>,
    EQ_EITHER: Eq<Kind<F, Either<Throwable, Int>>>,
    EQ_UNIT: Eq<Kind<F, Unit>>,
    ctx: CoroutineContext = Dispatchers.Default,
    testStackSafety: Boolean = true
  ): List<Law> =
    AsyncLaws.laws(CF, EQ, EQ_EITHER, testStackSafety) + listOf(
      Law("Concurrent Laws: cancel on bracket releases") { CF.cancelOnBracketReleases(EQ, ctx) },
      Law("Concurrent Laws: acquire is not cancelable") { CF.acquireBracketIsNotCancelable(EQ, ctx) },
      Law("Concurrent Laws: release is not cancelable") { CF.releaseBracketIsNotCancelable(EQ, ctx) },
      Law("Concurrent Laws: async cancelable coherence") { CF.asyncCancelableCoherence(EQ) },
      Law("Concurrent Laws: cancelable cancelableF coherence") { CF.cancelableCancelableFCoherence(EQ) },
      Law("Concurrent Laws: cancelable should run CancelToken on cancel") { CF.cancelableReceivesCancelSignal(EQ, ctx) },
      Law("Concurrent Laws: cancelableF should run CancelToken on cancel") { CF.cancelableFReceivesCancelSignal(EQ, ctx) },
      Law("Concurrent Laws: async can cancel upstream") { CF.asyncCanCancelUpstream(EQ, ctx) },
      Law("Concurrent Laws: async should run KindConnection on Fiber#cancel") { CF.asyncShouldRunKindConnectionOnCancel(EQ, ctx) },
      Law("Concurrent Laws: asyncF register can be cancelled") { CF.asyncFRegisterCanBeCancelled(EQ, ctx) },
      Law("Concurrent Laws: asyncF can cancel upstream") { CF.asyncFCanCancelUpstream(EQ, ctx) },
      Law("Concurrent Laws: asyncF should run KindConnection on Fiber#cancel") { CF.asyncFShouldRunKindConnectionOnCancel(EQ, ctx) },
      Law("Concurrent Laws: start join is identity") { CF.startJoinIsIdentity(EQ, ctx) },
      Law("Concurrent Laws: join is idempotent") { CF.joinIsIdempotent(EQ, ctx) },
      Law("Concurrent Laws: start cancel is unit") { CF.startCancelIsUnit(EQ_UNIT, ctx) },
      Law("Concurrent Laws: uncancelable mirrors source") { CF.uncancelableMirrorsSource(EQ) },
      Law("Concurrent Laws: race pair mirrors left winner") { CF.racePairMirrorsLeftWinner(EQ, ctx) },
      Law("Concurrent Laws: race pair mirrors right winner") { CF.racePairMirrorsRightWinner(EQ, ctx) },
      Law("Concurrent Laws: race pair can cancel loser") { CF.racePairCanCancelsLoser(EQ, ctx) },
      Law("Concurrent Laws: race pair can join left") { CF.racePairCanJoinLeft(EQ, ctx) },
      Law("Concurrent Laws: race pair can join right") { CF.racePairCanJoinRight(EQ, ctx) },
      Law("Concurrent Laws: cancelling race pair cancels both") { CF.racePairCancelCancelsBoth(EQ, ctx) },
      Law("Concurrent Laws: race pair is cancellable by participants") { CF.racePairCanBeCancelledByParticipants(EQ, ctx) },
      Law("Concurrent Laws: race triple mirrors left winner") { CF.raceTripleMirrorsLeftWinner(EQ, ctx) },
      Law("Concurrent Laws: race triple mirrors middle winner") { CF.raceTripleMirrorsMiddleWinner(EQ, ctx) },
      Law("Concurrent Laws: race triple mirrors right winner") { CF.raceTripleMirrorsRightWinner(EQ, ctx) },
      Law("Concurrent Laws: race triple can cancel loser") { CF.raceTripleCanCancelsLoser(EQ, ctx) },
      Law("Concurrent Laws: race triple can join left") { CF.raceTripleCanJoinLeft(EQ, ctx) },
      Law("Concurrent Laws: race triple can join middle") { CF.raceTripleCanJoinMiddle(EQ, ctx) },
      Law("Concurrent Laws: race triple can join right") { CF.raceTripleCanJoinRight(EQ, ctx) },
      Law("Concurrent Laws: race triple is cancellable by participants") { CF.raceTripleCanBeCancelledByParticipants(EQ, ctx) },
      Law("Concurrent Laws: cancelling race triple cancels all") { CF.raceTripleCancelCancelsAll(EQ, ctx) },
      Law("Concurrent Laws: race mirrors left winner") { CF.raceMirrorsLeftWinner(EQ, ctx) },
      Law("Concurrent Laws: race mirrors right winner") { CF.raceMirrorsRightWinner(EQ, ctx) },
      Law("Concurrent Laws: race cancels loser") { CF.raceCancelsLoser(EQ, ctx) },
      Law("Concurrent Laws: race cancels both") { CF.raceCancelCancelsBoth(EQ, ctx) },
      Law("Concurrent Laws: race is cancellable by participants") { CF.raceCanBeCancelledByParticipants(EQ, ctx) },
      Law("Concurrent Laws: parallel map cancels both") { CF.parMapCancelCancelsBoth(EQ, ctx) },
      Law("Concurrent Laws: parallel is cancellable by participants") { CF.parMapCanBeCancelledByParticipants(EQ, ctx) },
      Law("Concurrent Laws: action concurrent with pure value is just action") { CF.actionConcurrentWithPureValueIsJustAction(EQ, ctx) }
    )

  fun <F> Concurrent<F>.cancelOnBracketReleases(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) {
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val startLatch = Promise<F, Int>(this@cancelOnBracketReleases).bind() // A promise that `use` was executed
        val exitLatch = Promise<F, Int>(this@cancelOnBracketReleases).bind() // A promise that `release` was executed

        val (_, cancel) = ctx.startFiber(just(i).bracketCase(
          use = { a -> startLatch.complete(a).flatMap { never<Int>() } },
          release = { r, exitCase ->
            when (exitCase) {
              is ExitCase.Canceled -> exitLatch.complete(r) // Fulfil promise that `release` was executed with Canceled
              else -> just(Unit)
            }
          }
        )).bind() // Fork execution, allowing us to cancel it later

        val waitStart = startLatch.get().bind() // Waits on promise of `use`
        ctx.startFiber(cancel).bind() // Cancel bracketCase
        val waitExit = exitLatch.get().bind() // Observes cancellation via bracket's `release`

        waitStart + waitExit
      }.equalUnderTheLaw(just(i + i), EQ)
    }
  }

  fun <F> Concurrent<F>.acquireBracketIsNotCancelable(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.int()) { a, b ->
      fx.concurrent {
        val mvar = MVar(a, this@acquireBracketIsNotCancelable).bind()
        val p = Promise.uncancelable<F, Unit>(this@acquireBracketIsNotCancelable).bind()
        val task = p.complete(Unit).flatMap { mvar.put(b) }
          .bracket(use = { never<Int>() }, release = { just(Unit) })
        val (_, cancel) = ctx.startFiber(task).bind()
        p.get().bind()
        ctx.startFiber(cancel).bind()
        continueOn(ctx)
        mvar.take().bind()
        mvar.take().bind()
      }.equalUnderTheLaw(just(b), EQ)
    }

  fun <F> Concurrent<F>.releaseBracketIsNotCancelable(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.int()) { a, b ->
      fx.concurrent {
        val mvar = MVar(a, this@releaseBracketIsNotCancelable).bind()
        val p = Promise.uncancelable<F, Unit>(this@releaseBracketIsNotCancelable).bind()
        val task = p.complete(Unit)
          .bracket(use = { never<Int>() }, release = { mvar.put(b) })
        val (_, cancel) = ctx.startFiber(task).bind()
        p.get().bind()
        ctx.startFiber(cancel).bind()
        continueOn(ctx)
        mvar.take().bind()
        mvar.take().bind()
      }.equalUnderTheLaw(just(b), EQ)
    }

  fun <F> Concurrent<F>.asyncCancelableCoherence(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.either(Gen.throwable(), Gen.int())) { eith ->
      async<Int> { cb -> cb(eith) }
        .equalUnderTheLaw(cancelable { cb -> cb(eith); just<Unit>(Unit) }, EQ)
    }

  fun <F> Concurrent<F>.cancelableCancelableFCoherence(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.either(Gen.throwable(), Gen.int())) { eith ->
      cancelable<Int> { cb -> cb(eith); just<Unit>(Unit) }
        .equalUnderTheLaw(cancelableF { cb -> delay { cb(eith); just<Unit>(Unit) } }, EQ)
    }

  fun <F> Concurrent<F>.cancelableReceivesCancelSignal(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val release = Promise.uncancelable<F, Int>(this@cancelableReceivesCancelSignal).bind()
        val cancelToken: CancelToken<F> = release.complete(i)
        val latch = CountDownLatch(1)

        val (_, cancel) = ctx.startFiber(cancelable<Unit> {
          latch.countDown()
          cancelToken
        }).bind()

        ctx.shift().followedBy(asyncF<Unit> { cb ->
          delay { latch.await(500, TimeUnit.MILLISECONDS) }
            .map { cb(Right(Unit)) }
        }).bind()

        cancel.bind()
        release.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.cancelableFReceivesCancelSignal(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val release = Promise<F, Int>(this@cancelableFReceivesCancelSignal).bind()
        val latch = Promise<F, Unit>(this@cancelableFReceivesCancelSignal).bind()
        val async = cancelableF<Unit> {
          latch.complete(Unit)
            .map { release.complete(i) }
        }
        val (_, cancel) = ctx.startFiber(async).bind()
        asyncF<Unit> { cb -> latch.get().map { cb(Right(it)) } }.bind()
        cancel.bind()
        release.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.asyncCanCancelUpstream(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val latch = Promise<F, Int>(this@asyncCanCancelUpstream).bind()
        val cancelToken = AtomicReference<CancelToken<F>>()
        val cancelLatch = CountDownLatch(1)

        val upstream = async<Unit> { conn, cb ->
          conn.push(latch.complete(i))
          cb(Right(Unit))
        }

        val downstream = async<Unit> { conn, _ ->
          cancelToken.set(conn.cancel())
          cancelLatch.countDown()
        }

        ctx.startFiber(upstream.followedBy(downstream)).bind()

        ctx.startFiber(delay(ctx) {
          cancelLatch.await(500, TimeUnit.MILLISECONDS)
        }.flatMap { cancelToken.get() ?: raiseError(AssertionError("CancelToken was not set.")) }
        ).bind()

        latch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.asyncShouldRunKindConnectionOnCancel(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val latch = Promise<F, Int>(this@asyncShouldRunKindConnectionOnCancel).bind()
        val startLatch = CountDownLatch(1)

        val (_, cancel) = ctx.startFiber(async<Unit> { conn, _ ->
          conn.push(latch.complete(i))
          startLatch.countDown()
        }).bind()

        delay(ctx) {
          startLatch.await(500, TimeUnit.MILLISECONDS)
        }.followedBy(cancel).bind()

        latch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.asyncFRegisterCanBeCancelled(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val release = Promise<F, Int>(this@asyncFRegisterCanBeCancelled).bind()
        val acquire = Promise<F, Unit>(this@asyncFRegisterCanBeCancelled).bind()
        val task = asyncF<Unit> { _, _ ->
          acquire.complete(Unit).bracket(use = { never<Unit>() }, release = { release.complete(i) })
        }
        val (_, cancel) = ctx.startFiber(task).bind()
        acquire.get().bind()
        ctx.startFiber(cancel).bind()
        release.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.asyncFCanCancelUpstream(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val latch = Promise<F, Int>(this@asyncFCanCancelUpstream).bind()
        val upstream = async<Unit> { conn, cb ->
          conn.push(latch.complete(i))
          cb(Right(Unit))
        }
        val downstream = asyncF<Unit> { conn, _ ->
          conn.cancel()
        }

        ctx.startFiber(upstream.followedBy(downstream)).bind()

        latch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.asyncFShouldRunKindConnectionOnCancel(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      fx.concurrent {
        val latch = Promise<F, Int>(this@asyncFShouldRunKindConnectionOnCancel).bind()
        val startLatch = Promise<F, Unit>(this@asyncFShouldRunKindConnectionOnCancel).bind()

        val (_, cancel) = ctx.startFiber(asyncF<Unit> { conn, _ ->
          conn.push(latch.complete(i))
          // Wait with cancellation until it is run, if it doesn't run its cancellation is also doesn't run.
          startLatch.complete(Unit)
        }).bind()

        startLatch.get().flatMap { cancel }.bind()

        latch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.startJoinIsIdentity(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().applicativeError(this)) { fa ->
      ctx.startFiber(fa).flatMap { it.join() }.equalUnderTheLaw(fa, EQ)
    }

  fun <F> Concurrent<F>.joinIsIdempotent(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      Promise<F, Int>(this@joinIsIdempotent).flatMap { p ->
        ctx.startFiber(p.complete(i))
          .flatMap { (join, _) -> join.followedBy(join) }
          .flatMap { p.get() }
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.startCancelIsUnit(EQ_UNIT: Eq<Kind<F, Unit>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().applicativeError(this)) { fa ->
      ctx.startFiber(fa).flatMap { (_, cancel) -> cancel }
        .equalUnderTheLaw(just<Unit>(Unit), EQ_UNIT)
    }

  fun <F> Concurrent<F>.uncancelableMirrorsSource(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int()) { i ->
      just(i).uncancelable().equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceMirrorsLeftWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().applicativeError(this)) { fa ->
      ctx.raceN(fa, never<Int>()).flatMap { either ->
        either.fold({ just(it) }, { raiseError(IllegalStateException("never() finished race")) })
      }.equalUnderTheLaw(fa, EQ)
    }

  fun <F> Concurrent<F>.raceMirrorsRightWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().applicativeError(this)) { fa ->
      ctx.raceN(never<Int>(), fa).flatMap { either ->
        either.fold({ raiseError<Int>(IllegalStateException("never() finished race")) }, { just(it) })
      }.equalUnderTheLaw(fa, EQ)
    }

  fun <F> Concurrent<F>.raceCancelsLoser(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.either(Gen.throwable(), Gen.string()), Gen.bool(), Gen.int()) { eith, leftWins, i ->
      fx.concurrent {
        val s = Semaphore(0L, this@raceCancelsLoser).bind()
        val promise = Promise.uncancelable<F, Int>(this@raceCancelsLoser).bind()
        val winner = s.acquire().flatMap { async<String> { cb -> cb(eith) } }
        val loser = s.release().bracket(use = { never<Int>() }, release = { promise.complete(i) })
        val race =
          if (leftWins) ctx.raceN(winner, loser)
          else ctx.raceN(loser, winner)

        race.attempt().flatMap { promise.get() }.bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceCancelCancelsBoth(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.int()) { a, b ->
      fx.concurrent {
        val s = Semaphore(0L, this@raceCancelCancelsBoth).bind()
        val pa = Promise<F, Int>(this@raceCancelCancelsBoth).bind()
        val pb = Promise<F, Int>(this@raceCancelCancelsBoth).bind()

        val loserA = s.release().bracket(use = { never<String>() }, release = { pa.complete(a) })
        val loserB = s.release().bracket(use = { never<Int>() }, release = { pb.complete(b) })

        val (_, cancelRace) = ctx.startFiber(ctx.raceN(loserA, loserB)).bind()
        s.acquireN(2L).flatMap { cancelRace }.bind()
        pa.get().bind() + pb.get().bind()
      }.equalUnderTheLaw(just(a + b), EQ)
    }

  fun <F> Concurrent<F>.raceCanBeCancelledByParticipants(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.bool()) { i, shouldLeftCancel ->
      fx.concurrent {
        val endLatch = Promise<F, Int>(this@raceCanBeCancelledByParticipants).bind()
        val startLatch = Promise<F, Unit>(this@raceCanBeCancelledByParticipants).bind()

        val cancel = asyncF<Unit> { conn, cb -> startLatch.get().flatMap { conn.cancel().map { cb(Right(Unit)) } } }
        val loser = startLatch.complete(Unit) // guarantees that both cancel & loser started
          .bracket(use = { never<Int>() }, release = { endLatch.complete(i) })

        if (shouldLeftCancel) ctx.startFiber(ctx.raceN(cancel, loser)).bind()
        else ctx.startFiber(ctx.raceN(loser, cancel)).bind()

        endLatch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.racePairMirrorsLeftWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().applicativeError(this)) { fa ->
      val never = never<Int>()
      val received = ctx.racePair(fa, never).flatMap { either ->
        either.fold({ a, fiberB ->
          fiberB.cancel().map { a }
        }, { _, _ -> raiseError(AssertionError("never() finished race")) })
      }

      received.equalUnderTheLaw(ctx.raceN(fa, never).map { it.fold(::identity, ::identity) }, EQ)
    }

  fun <F> Concurrent<F>.racePairMirrorsRightWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().applicativeError(this)) { fa ->
      val never = never<Int>()
      val received = ctx.racePair(never, fa).flatMap { either ->
        either.fold({ _, _ ->
          raiseError<Int>(AssertionError("never() finished race"))
        }, { fiberA, b -> fiberA.cancel().map { b } })
      }

      received.equalUnderTheLaw(ctx.raceN(never, fa).map { it.fold(::identity, ::identity) }, EQ)
    }

  fun <F> Concurrent<F>.racePairCanCancelsLoser(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.either(Gen.throwable(), Gen.string()), Gen.bool(), Gen.int()) { eith, leftWinner, i ->
      val received = fx.concurrent {
        val s = Semaphore(0L, this@racePairCanCancelsLoser).bind()
        val p = Promise.uncancelable<F, Int>(this@racePairCanCancelsLoser).bind()
        val winner = s.acquire().flatMap { async<String> { cb -> cb(eith) } }
        val loser = s.release().bracket(use = { never<String>() }, release = { p.complete(i) })
        val race = if (leftWinner) ctx.racePair(winner, loser)
        else ctx.racePair(loser, winner)

        race.attempt()
          .flatMap { attempt ->
            attempt.fold({ p.get() },
              {
                it.fold(
                  { _, fiberB -> ctx.startFiber(fiberB.cancel()).flatMap { p.get() } },
                  { fiberA, _ -> ctx.startFiber(fiberA.cancel()).flatMap { p.get() } })
              })
          }.bind()
      }

      received.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.racePairCanJoinLeft(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      Promise<F, Int>(this@racePairCanJoinLeft).flatMap { p ->
        ctx.racePair(p.get(), just(Unit)).flatMap { eith ->
          eith.fold(
            { unit, _ -> just(unit) },
            { fiber, _ -> p.complete(i).flatMap { fiber.join() } }
          )
        }
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.racePairCanJoinRight(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      Promise<F, Int>(this@racePairCanJoinRight).flatMap { p ->
        ctx.racePair(just(Unit), p.get()).flatMap { eith ->
          eith.fold(
            { _, fiber -> p.complete(i).flatMap { fiber.join() } },
            { _, unit -> just(unit) }
          )
        }
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.racePairCancelCancelsBoth(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.int()) { a, b ->
      fx.concurrent {
        val s = Semaphore(0L, this@racePairCancelCancelsBoth).bind()
        val pa = Promise<F, Int>(this@racePairCancelCancelsBoth).bind()
        val pb = Promise<F, Int>(this@racePairCancelCancelsBoth).bind()

        val loserA: Kind<F, Int> = s.release().bracket(use = { never<Int>() }, release = { pa.complete(a) })
        val loserB: Kind<F, Int> = s.release().bracket(use = { never<Int>() }, release = { pb.complete(b) })

        val (_, cancelRacePair) = ctx.startFiber(ctx.racePair(loserA, loserB)).bind()

        s.acquireN(2L).flatMap { cancelRacePair }.bind()
        pa.get().bind() + pb.get().bind()
      }.equalUnderTheLaw(just(a + b), EQ)
    }

  fun <F> Concurrent<F>.racePairCanBeCancelledByParticipants(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.bool()) { i, shouldLeftCancel ->
      fx.concurrent {
        val endLatch = Promise<F, Int>(this@racePairCanBeCancelledByParticipants).bind()
        val startLatch = Promise<F, Unit>(this@racePairCanBeCancelledByParticipants).bind()

        val cancel = asyncF<Unit> { conn, cb -> startLatch.get().flatMap { conn.cancel().map { cb(Right(Unit)) } } }

        val loser = startLatch.complete(Unit) // guarantees that both cancel & loser actually started
          .bracket(use = { never<Int>() }, release = { endLatch.complete(i) })

        if (shouldLeftCancel) ctx.startFiber(ctx.racePair(cancel, loser)).bind()
        else ctx.startFiber(ctx.racePair(loser, cancel)).bind()

        endLatch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceTripleMirrorsLeftWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int().applicativeError(this)) { fa ->
      val never = never<Int>()
      val received = ctx.raceTriple(fa, never, never).flatMap { either ->
        either.fold(
          { a, fiberB, fiberC -> fiberB.cancel().followedBy(fiberC.cancel()).map { a } },
          { _, _, _ -> raiseError(AssertionError("never() finished race")) },
          { _, _, _ -> raiseError(AssertionError("never() finished race")) })
      }

      received.equalUnderTheLaw(ctx.raceN(fa, never, never).map { it.fold(::identity, ::identity, ::identity) }, EQ)
    }

  fun <F> Concurrent<F>.raceTripleMirrorsMiddleWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int().applicativeError(this)) { fa ->
      val never = never<Int>()
      val received = ctx.raceTriple(never, fa, never).flatMap { either ->
        either.fold(
          { _, _, _ -> raiseError<Int>(AssertionError("never() finished race")) },
          { fiberA, b, fiberC -> fiberA.cancel().followedBy(fiberC.cancel()).map { b } },
          { _, _, _ -> raiseError(AssertionError("never() finished race")) })
      }

      received.equalUnderTheLaw(ctx.raceN(never, fa, never).map { it.fold(::identity, ::identity, ::identity) }, EQ)
    }

  fun <F> Concurrent<F>.raceTripleMirrorsRightWinner(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int().applicativeError(this)) { fa ->
      val never = never<Int>()
      val received = ctx.raceTriple(never, never, fa).flatMap { either ->
        either.fold(
          { _, _, _ -> raiseError<Int>(AssertionError("never() finished race")) },
          { _, _, _ -> raiseError(AssertionError("never() finished race")) },
          { fiberA, fiberB, c -> fiberA.cancel().followedBy(fiberB.cancel()).map { c } })
      }

      received.equalUnderTheLaw(ctx.raceN(never, never, fa).map { it.fold(::identity, ::identity, ::identity) }, EQ)
    }

  fun <F> Concurrent<F>.raceTripleCanCancelsLoser(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.either(Gen.throwable(), Gen.string()), Gen.from(listOf(1, 2, 3)), Gen.int(), Gen.int()) { eith, leftWinner, a, b ->
      val received = fx.concurrent {
        val s = Semaphore(0L, this@raceTripleCanCancelsLoser).bind()
        val pa = Promise.uncancelable<F, Int>(this@raceTripleCanCancelsLoser).bind()
        val pb = Promise.uncancelable<F, Int>(this@raceTripleCanCancelsLoser).bind()

        val winner = s.acquireN(2).flatMap { async<String> { cb -> cb(eith) } }
        val loser = s.release().bracket(use = { never<String>() }, release = { pa.complete(a) })
        val loser2 = s.release().bracket(use = { never<String>() }, release = { pb.complete(b) })

        val race = when (leftWinner) {
          1 -> ctx.raceTriple(winner, loser, loser2)
          2 -> ctx.raceTriple(loser, winner, loser2)
          else -> ctx.raceTriple(loser, loser2, winner)
        }

        val combinePromises = pa.get().flatMap { a -> pb.get().map { b -> a + b } }

        race.attempt()
          .flatMap { attempt ->
            attempt.fold({ combinePromises },
              {
                it.fold(
                  { _, fiberB, fiberC ->
                    ctx.startFiber(fiberB.cancel().followedBy(fiberC.cancel())).flatMap { combinePromises } },
                  { fiberA, _, fiberC ->
                    ctx.startFiber(fiberA.cancel().followedBy(fiberC.cancel())).flatMap { combinePromises } },
                  { fiberA, fiberB, _ ->
                    ctx.startFiber(fiberA.cancel().followedBy(fiberB.cancel())).flatMap { combinePromises } })
              })
          }.bind()
      }

      received.equalUnderTheLaw(just(a + b), EQ)
    }

  fun <F> Concurrent<F>.raceTripleCanJoinLeft(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      Promise<F, Int>(this@raceTripleCanJoinLeft).flatMap { p ->
        ctx.raceTriple(p.get(), just(Unit), never<Unit>()).flatMap { result ->
          result.fold(
            { _, _, _ -> raiseError<Int>(AssertionError("Promise#get can never win race")) },
            { fiber, _, _ -> p.complete(i).flatMap { fiber.join() } },
            { _, _, _ -> raiseError(AssertionError("never() can never win race")) }
          )
        }
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceTripleCanJoinMiddle(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      Promise<F, Int>(this@raceTripleCanJoinMiddle).flatMap { p ->
        ctx.raceTriple(just(Unit), p.get(), never<Unit>()).flatMap { result ->
          result.fold(
            { _, fiber, _ -> p.complete(i).flatMap { fiber.join() } },
            { _, _, _ -> raiseError(AssertionError("Promise#get can never win race")) },
            { _, _, _ -> raiseError(AssertionError("never() can never win race")) }
          )
        }
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceTripleCanJoinRight(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int()) { i ->
      Promise<F, Int>(this@raceTripleCanJoinRight).flatMap { p ->
        ctx.raceTriple(just(Unit), never<Unit>(), p.get()).flatMap { result ->
          result.fold(
            { _, _, fiber -> p.complete(i).flatMap { fiber.join() } },
            { _, _, _ -> raiseError(AssertionError("never() can never win race")) },
            { _, _, _ -> raiseError(AssertionError("Promise#get can never win race")) }
          )
        }
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceTripleCanBeCancelledByParticipants(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.from(listOf(1, 2, 3))) { i, shouldCancel ->
      fx.concurrent {
        val endLatch = Promise<F, Int>(this@raceTripleCanBeCancelledByParticipants).bind()
        val startLatch = Promise<F, Unit>(this@raceTripleCanBeCancelledByParticipants).bind()
        val start2Latch = Promise<F, Unit>(this@raceTripleCanBeCancelledByParticipants).bind()

        val cancel = asyncF<Unit> { conn, cb ->
          startLatch.get().followedBy(start2Latch.get())
            .flatMap { conn.cancel().map { cb(Right(Unit)) } }
        }

        val loser = startLatch.complete(Unit) // guarantees that both cancel & loser actually started
          .bracket(use = { never<Int>() }, release = { endLatch.complete(i) })
        val loser2 = start2Latch.complete(Unit) // guarantees that both cancel & loser actually started
          .bracket(use = { never<Int>() }, release = { endLatch.complete(i) })

        when (shouldCancel) {
          1 -> ctx.startFiber(ctx.raceTriple(cancel, loser, loser2)).bind()
          2 -> ctx.startFiber(ctx.raceTriple(loser, cancel, loser2)).bind()
          else -> ctx.startFiber(ctx.raceTriple(loser, loser2, cancel)).bind()
        }

        endLatch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.raceTripleCancelCancelsAll(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.int(), Gen.int()) { a, b, c ->
      fx.concurrent {
        val s = Semaphore(0L, this@raceTripleCancelCancelsAll).bind()
        val pa = Promise<F, Int>(this@raceTripleCancelCancelsAll).bind()
        val pb = Promise<F, Int>(this@raceTripleCancelCancelsAll).bind()
        val pc = Promise<F, Int>(this@raceTripleCancelCancelsAll).bind()

        val loserA: Kind<F, Int> = s.release().bracket(use = { never<Int>() }, release = { pa.complete(a) })
        val loserB: Kind<F, Int> = s.release().bracket(use = { never<Int>() }, release = { pb.complete(b) })
        val loserC: Kind<F, Int> = s.release().bracket(use = { never<Int>() }, release = { pc.complete(c) })

        val (_, cancelRacePair) = ctx.startFiber(ctx.raceTriple(loserA, loserB, loserC)).bind()

        s.acquireN(3L).flatMap { cancelRacePair }.bind()
        pa.get().bind() + pb.get().bind() + pc.get().bind()
      }.equalUnderTheLaw(just(a + b + c), EQ)
    }

  fun <F> Concurrent<F>.parMapCancelCancelsBoth(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.int()) { a, b ->
      fx.concurrent {
        val s = Semaphore(0L, this@parMapCancelCancelsBoth).bind()
        val pa = Promise<F, Int>(this@parMapCancelCancelsBoth).bind()
        val pb = Promise<F, Int>(this@parMapCancelCancelsBoth).bind()

        val loserA = s.release().bracket(use = { never<String>() }, release = { pa.complete(a) })
        val loserB = s.release().bracket(use = { never<Int>() }, release = { pb.complete(b) })

        val (_, cancelParMapN) = ctx.startFiber(ctx.parMapN(loserA, loserB, ::Tuple2)).bind()
        s.acquireN(2L).flatMap { cancelParMapN }.bind()
        pa.get().bind() + pb.get().bind()
      }.equalUnderTheLaw(just(a + b), EQ)
    }

  fun <F> Concurrent<F>.parMapCanBeCancelledByParticipants(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext) =
    forAll(Gen.int(), Gen.bool()) { i, shouldLeftCancel ->
      fx.concurrent {
        val endLatch = Promise<F, Int>(this@parMapCanBeCancelledByParticipants).bind()
        val startLatch = Promise<F, Unit>(this@parMapCanBeCancelledByParticipants).bind()

        val cancel = asyncF<Unit> { conn, cb -> startLatch.get().flatMap { conn.cancel().map { cb(Right(Unit)) } } }
        val loser = startLatch.complete(Unit).bracket(use = { never<Int>() }, release = { endLatch.complete(i) })

        if (shouldLeftCancel) ctx.startFiber(ctx.parMapN(cancel, loser, ::Tuple2)).bind()
        else ctx.startFiber(ctx.parMapN(loser, cancel, ::Tuple2)).bind()

        endLatch.get().bind()
      }.equalUnderTheLaw(just(i), EQ)
    }

  fun <F> Concurrent<F>.actionConcurrentWithPureValueIsJustAction(EQ: Eq<Kind<F, Int>>, ctx: CoroutineContext): Unit =
    forAll(Gen.int().map(::just), Gen.int()) { fa, i ->
      ctx.startFiber(i.just()).flatMap { (join, _) ->
        fa.flatMap {
          join.map { i }
        }
      }.equalUnderTheLaw(just(i), EQ)
    }
}
