package arrow.test.laws

import arrow.Kind
import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import arrow.fx.Promise
import arrow.fx.typeclasses.Async
import arrow.fx.typeclasses.ExitCase
import arrow.test.generators.applicativeError
import arrow.test.generators.either
import arrow.test.generators.functionAToB
import arrow.test.generators.intSmall
import arrow.test.generators.throwable
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll
import kotlinx.coroutines.newSingleThreadContext

object AsyncLaws {

  fun <F> laws(
    AC: Async<F>,
    EQ: Eq<Kind<F, Int>>,
    EQ_EITHER: Eq<Kind<F, Either<Throwable, Int>>>,
    testStackSafety: Boolean = true
  ): List<Law> =
    MonadDeferLaws.laws(AC, EQ, EQ_EITHER, testStackSafety = testStackSafety) + listOf(
      Law("Async Laws: success equivalence") { AC.asyncSuccess(EQ) },
      Law("Async Laws: error equivalence") { AC.asyncError(EQ) },
      Law("Async Laws: continueOn jumps threads") { AC.continueOn(EQ) },
      Law("Async Laws: async constructor") { AC.asyncConstructor(EQ) },
      Law("Async Laws: async can be derived from asyncF") { AC.asyncCanBeDerivedFromAsyncF(EQ) },
      Law("Async Laws: bracket release is called on completed or error") { AC.bracketReleaseIscalledOnCompletedOrError(EQ) },
      Law("Async Laws: continueOn on comprehensions") { AC.continueOnComprehension(EQ) },
      Law("Async Laws: effect calls suspend functions in the right dispatcher") { AC.effectCanCallSuspend(EQ) },
      Law("Async Laws: effect is equivalent to later") { AC.effectEquivalence(EQ) }
    )

  fun <F> Async<F>.asyncSuccess(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int()) { num: Int ->
      async { ff: (Either<Throwable, Int>) -> Unit -> ff(Right(num)) }.equalUnderTheLaw(just(num), EQ)
    }

  fun <F> Async<F>.asyncError(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.throwable()) { e: Throwable ->
      async { ff: (Either<Throwable, Int>) -> Unit -> ff(Left(e)) }.equalUnderTheLaw(raiseError(e), EQ)
    }

  fun <F> Async<F>.continueOn(EQ: Eq<Kind<F, Int>>): Unit =
    forFew(5, Gen.intSmall(), Gen.intSmall()) { threadId1: Int, threadId2: Int ->
      Unit.just()
        .continueOn(newSingleThreadContext(threadId1.toString()))
        .map { getCurrentThread() }
        .continueOn(newSingleThreadContext(threadId2.toString()))
        .map { it + getCurrentThread() }
        .equalUnderTheLaw(just(threadId1 + threadId2), EQ)
    }

  fun <F> Async<F>.asyncConstructor(EQ: Eq<Kind<F, Int>>): Unit =
    forFew(5, Gen.intSmall(), Gen.intSmall()) { threadId1: Int, threadId2: Int ->
      later(newSingleThreadContext(threadId1.toString())) { getCurrentThread() }
        .flatMap {
          later(newSingleThreadContext(threadId2.toString())) { it + getCurrentThread() }
        }
        .equalUnderTheLaw(just(threadId1 + threadId2), EQ)
    }

  fun <F> Async<F>.continueOnComprehension(EQ: Eq<Kind<F, Int>>): Unit =
    forFew(5, Gen.intSmall(), Gen.intSmall()) { threadId1: Int, threadId2: Int ->
      fx.async {
        continueOn(newSingleThreadContext(threadId1.toString()))
        val t1: Int = getCurrentThread()
        continueOn(newSingleThreadContext(threadId2.toString()))
        t1 + getCurrentThread()
      }.equalUnderTheLaw(just(threadId1 + threadId2), EQ)
    }

  fun <F> Async<F>.asyncCanBeDerivedFromAsyncF(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.either(Gen.throwable(), Gen.int())) { eith ->
      val k: ((Either<Throwable, Int>) -> Unit) -> Unit = { f ->
        f(eith)
      }

      async(k).equalUnderTheLaw(asyncF { cb -> later { k(cb) } }, EQ)
    }

  fun <F> Async<F>.bracketReleaseIscalledOnCompletedOrError(EQ: Eq<Kind<F, Int>>) {
    forAll(Gen.string().applicativeError(this), Gen.int()) { fa, b ->
      Promise.uncancelable<F, Int>(this@bracketReleaseIscalledOnCompletedOrError).flatMap { promise ->
        val br = later { promise }.bracketCase(use = { fa }, release = { r, exitCase ->
          when (exitCase) {
            is ExitCase.Completed -> r.complete(b)
            is ExitCase.Error -> r.complete(b)
            else -> just<Unit>(Unit)
          }
        })

        asyncF<Unit> { cb -> later { cb(Right(Unit)) }.flatMap { br.attempt().`as`(Unit) } }
          .flatMap { promise.get() }
      }.equalUnderTheLaw(just(b), EQ)
    }
  }

  fun <F> Async<F>.effectCanCallSuspend(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int()) { id ->
      val fs: suspend () -> String = { Thread.currentThread().name }

      effect(newSingleThreadContext(id.toString())) {
        fs().toInt()
      }.equalUnderTheLaw(just(id), EQ)
    }

  fun <F> Async<F>.effectEquivalence(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.functionAToB<Unit, Int>(Gen.constant(0))) { f ->
      val fs: suspend () -> Int = { f(Unit) }

      val effect = effect(newSingleThreadContext("1")) { fs() }

      val continueOn = later(newSingleThreadContext("2")) { f(Unit) }

      effect.equalUnderTheLaw(continueOn, EQ)
    }

  // Turns out that kotlinx.coroutines decides to rewrite thread names
  private fun getCurrentThread() =
    Thread.currentThread().name.substringBefore(' ').toInt()
}
