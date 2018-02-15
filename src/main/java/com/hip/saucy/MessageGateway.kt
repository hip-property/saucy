package com.hip.saucy

import org.funktionale.either.Either
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface MessageGateway {

   /**
    * Convenience method that dispatches the
    * provided event, and returns it back to the caller,
    * useful for chaining methods.
    *
    * (Mainly used in tests)
    */
   fun <T : Dispatchable> submit(dispatchable: T): Pair<Mono<out Event<T>>, Flux<Any>>

   fun dispatch(dispatchable: Dispatchable): Flux<Any> {
      return submit(dispatchable).second
   }

   fun <TFailure, TSuccess> dispatchCommandEvent(commandEvent: CommandEvent<TFailure, TSuccess>): Mono<Either<TFailure, TSuccess>>
}
