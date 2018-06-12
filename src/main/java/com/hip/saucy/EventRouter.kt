/*-
 * =========================================================BeginLicense
 * Saucy
 * .
 * Copyright (C) 2018 HiP Property
 * .
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ===========================================================EndLicense
 */
package com.hip.saucy

import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import com.hip.saucy.utils.log
import org.funktionale.either.Either
import org.funktionale.either.merge
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.javaMethod

class EventRouter internal constructor(
   private val handlers: Multimap<Class<*>, EventHandlerInvoker>,
   val eventStore: EventStore,
   replayOnStartup: Boolean = false
) : MessageGateway {

   private var isReplaying = false
   private val eventFactory = EventFactory()

   private var hasDispatched = false

   init {
      if (replayOnStartup)
         replay()
   }

   /**
    * Replays the messages stored in the event store.
    * In order to preserve integrity, replaying is only
    * allowed once, and only prior to dispatching other events,
    * so should be called on startup
    */
   fun replay() {
      if (hasDispatched) error("Cannot replay once a message has been dispatched")

      isReplaying = true
      log().info("Starting to replay event store")
      eventStore.replay()
         .subscribe { event -> dispatch(event).subscribe() }
      log().info("Replay complete")
      isReplaying = false
   }

   private fun KType.matchesInstance(instance: Any): Boolean {
      return instance::class.starProjectedType == this
   }

   private fun Iterable<KType>.matchesInstance(instance: Any): Boolean {
      return this.any { it.matchesInstance(instance) }
   }

   override fun <TFailure, TSuccess> dispatchCommandEvent(commandEvent: CommandEvent<TFailure, TSuccess>): Mono<Either<TFailure, TSuccess>> {
      val commandEventType = commandEvent::class
         .supertypes
         .first { it.isSubtypeOf(CommandEvent::class.starProjectedType) }


      val failureType = commandEventType.arguments[0].type!!
      val successType = commandEventType.arguments[1].type!!

      // Dispatch the command message, and watch the subsequent
      // event stream.
      // We're looking for an event that is either our success, or our
      // failure message.
      // Note - it could be presented as TSuccess, TFailure *or* Either<TFailure,TSuccess>
      // The first time that occurs, we capture it and return it out
      // of the Mono as the result of the command.
      return this.dispatch(commandEvent)
         .filter { isSuccessOrFailureMessage(failureType, successType, it) }
         .collectList()
         .map { messages ->
            val message = extractFirstMessage(messages)
            wrapMessageInEither<TFailure, TSuccess>(failureType, successType, message)
            // Handle any errors that were thrown
         }.doOnError { wrapErrorInEither<TFailure>(failureType, it) }
   }

   private fun <TFailure, TSuccess> wrapMessageInEither(failureType: KType, successType: KType, message: Any): Either<TFailure, TSuccess> {
      return when {
         failureType.matchesInstance(message) -> Either.left(message as TFailure)
         successType.matchesInstance(message) -> Either.right(message as TSuccess)
         else -> throw IllegalStateException("The message was neither TSuccess nor TFailure, despite previous filter passing.  Shouldn't happen")
      }
   }

   private fun <TFailure> wrapErrorInEither(failureType: KType, error: Throwable) {
      if (failureType.matchesInstance(error)) {
         Either.left(error as TFailure)
      } else {
         throw error
      }
   }

   private fun extractFirstMessage(messages: MutableList<Any>): Any {
      return messages.first().let { first ->
         when (first) {
         // If we got an either, get the enclosing type (left or right)
            is Either<*, *> -> first.merge()!!
            else -> first
         }
      }
   }

   private fun isSuccessOrFailureMessage(failureType: KType, successType: KType, messasge: Any): Boolean {
      return when {
         listOf(failureType, successType).matchesInstance(messasge) -> true
         messasge is Either<*, *> -> listOf(failureType, successType).matchesInstance(messasge.merge() ?: Unit)
         else -> false
      }
   }


   override fun <T : Dispatchable> submit(dispatchable: T): Pair<Mono<Event<T>>, Flux<Any>> {
      // If the payload we've been given isn't an actual event, wrap
      // it to make it one
      val event = wrapToEvent(dispatchable)
      val futureCommittedEvent = writeEvent(event)
      val flux = Flux.create<Any> { sink ->

         futureCommittedEvent.subscribe { committedEvent ->
            log().debug("Publishing event for ${committedEvent.simpleName}")
            // Note: the payload may be the original payload we were provided,
            // or the unwrapped value if it's been wrapped.
            getMessageTypes(committedEvent.payload::class.java)
               .flatMap { messageType -> handlers[messageType] }
               .distinct()
               .forEach { handler ->
                  log().debug("Message ${dispatchable::class.java.simpleName} matched handler ${handler.name}")
                  val result = handler.invoke(committedEvent.payload)
                  log().debug("Message ${dispatchable::class.java.simpleName} returned response of ${result::class.java} from ${handler.name} -- routing")
                  sink.next(result)
               }
            sink.complete()
         }
         // Call cache so that as new subscribers join, they are
         // replayed previous events, rather than triggering the flux to restart
      }.cache()


      if (!isReplaying) {
//       Responses are re-dispatched
         flux.subscribe { next ->
            when (next) {
               is NullOrVoidResponse -> {
               } // Do nothing
               is Flux<*> -> next.subscribe { redispatchEventMembers(it) }
               is Mono<*> -> next.subscribe { redispatchEventMembers(it) }
               is Iterable<*> -> next.filterNotNull().forEach { redispatchEventMembers(it) }
               is Dispatchable -> dispatch(next)
               else -> log().warn("Ignoring response of type ${next.javaClass} as it's not Dispatchable")
            }
         }
      }
      hasDispatched = true
      return futureCommittedEvent to flux
   }

   private fun <T : Dispatchable> wrapToEvent(dispatchable: T): Event<T> {
      return if (dispatchable is Event<*>) {
         dispatchable as Event<T>
      } else {
         eventFactory.eventOf(dispatchable)
      }
   }

   private fun redispatchEventMembers(member: Any) {
      when (member) {
         is Dispatchable -> this.dispatch(member)
         else -> log().warn("Ignoring response member of type ${member.javaClass} as it's not an event")
      }
   }


   private fun <T : Any> writeEvent(event: Event<T>): Mono<Event<out T>> {
      return if (isReplaying) {
         Mono.just(event)
      } else {
         eventStore.store(event)
      }
   }

   private fun getMessageTypes(clazz: Class<*>): List<Class<*>> {
      // NullOrVoidResponse is a special type, which cannot match any messages
      if (clazz == NullOrVoidResponse::class.java) return emptyList()

      val result = mutableListOf<Class<*>>()
      result.add(clazz)
      result.addAll(clazz.interfaces)
      if (clazz.superclass != null) {
         result.addAll(getMessageTypes(clazz.superclass))
      }
      return result
   }


   companion object {
      fun withMembers(vararg typesOrInstances: Any): EventRouterBuilder {
         val builder = EventRouterBuilder()
         typesOrInstances.forEach { instance ->
            @Suppress("UNCHECKED_CAST")
            when (instance) {
               is Class<*> -> builder.addParticipantType(instance as Class<Any>)
               else -> builder.addParticipant(instance)
            }
         }
         return builder
      }
   }
}

class EventRouterBuilder {

   private var eventStore: EventStore = MemoryEventStore()
   private val participants = mutableListOf<Pair<Class<*>, EventHandlerInvoker>>()
   private var replayOnStartup = false
   fun addParticipantType(type: Class<Any>): EventRouterBuilder {
      try {
         return addParticipant(type.newInstance())
      } catch (e: InstantiationException) {
         error("Cannot instantiate receiver instance - it must have a zero-arg constructor.  Consider instantiating it, and passing me the built instance.")
      }
   }

   fun addParticipant(receiver: Any): EventRouterBuilder {
      if (receiver is Class<*>) error("Don't pass a class - call addParticipantType() instead")
      val receiverType = receiver::class

      participants.addAll(receiverType.memberFunctions.filter { it.findAnnotation<MessageHandler>() != null }
         .map { it.javaMethod ?: TODO("KFunctions that don't contain a JavaMethod aren't currently supported") }
         .map { method ->
            val params = method.parameterTypes
            if (params.size != 1) error("Only single-arg methods are supported currently")
            val messageType = params[0]
            log().debug("Added handler for message type ${messageType.typeName} at ${method.name}")
            messageType to EventHandlerInvoker(receiver, method)
         }
      )
      return this
   }

   fun withEventStore(eventStore: EventStore): EventRouterBuilder {
      this.eventStore = eventStore
      return this
   }

   fun withPassThroughEventStore(): EventRouterBuilder {
      this.eventStore = PassThroughEventStore()
      return this
   }

   fun replayOnStartup(): EventRouterBuilder {
      this.replayOnStartup = true
      return this
   }

   fun build(): EventRouter {
      val map = ArrayListMultimap.create<Class<*>, EventHandlerInvoker>()
      participants.forEach { map.put(it.first, it.second) }
      return EventRouter(map, eventStore, replayOnStartup)
   }
}

/**
 * Marker type for when a method invocation
 * returns either null or void.
 * Allows for downstream mapping & filtering
 */
object NullOrVoidResponse

internal class EventHandlerInvoker(val instance: Any, val method: Method) {
   fun invoke(message: Any): Any {
      return method.invoke(instance, message) ?: NullOrVoidResponse
   }

   val name: String = "${instance.javaClass.name}::${method.name}"
}
